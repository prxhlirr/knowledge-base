"""
迁移单个 kb_document_* 物理索引到 v2 目标索引。

业务功能：
  创建权限字段类型正确的文档 chunk v2 索引，并通过 scroll + bulk 迁移历史 chunk 数据。

关键流程：
  1. 目标索引使用显式 mapping，确保 acl_tokens/source_index/index_code/owner_unit_code/visible_unit_codes 为 keyword；
  2. 迁移时只写入检索和权限需要的白名单字段，避免旧索引动态脏字段污染 v2 mapping；
  3. 默认 dry-run，不创建索引、不写入数据、不切 alias；
  4. 创建、写入、切 alias 分别由 --create-target、--execute、--switch-alias 显式控制；
  5. 不删除旧索引，回滚通过 alias 切回旧索引完成。
"""

import argparse
import json
import os
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, List, Optional

from dotenv import load_dotenv
from elasticsearch import Elasticsearch, helpers

load_dotenv()

DEFAULT_ES_HOST = os.getenv("ES_HOST", "http://localhost:9200")
DEFAULT_ES_USER = os.getenv("ES_USER", os.getenv("ES_USERNAME", ""))
DEFAULT_ES_PASS = os.getenv("ES_PASS", os.getenv("ES_PASSWORD", ""))
DEFAULT_READ_ALIAS = os.getenv("KB_DOCUMENT_READ_ALIAS", "kb_document")


@dataclass
class MigrationStats:
    """
    业务功能：记录文档 chunk v2 迁移计数。
    关键流程：扫描、预演、写入、失败分别计数，便于迁移后和 ES count 对账。
    """

    scanned: int = 0
    dry_run_writes: int = 0
    written: int = 0
    failed: int = 0


def create_es_client(es_host: str, es_user: str = "", es_pass: str = "") -> Elasticsearch:
    """
    业务功能：创建 Elasticsearch 客户端。
    关键流程：参数优先于环境变量，兼容本地、Docker 和离线服务器执行。
    """

    kwargs = {"hosts": [es_host], "request_timeout": 300}
    if es_user:
        kwargs["basic_auth"] = (es_user, es_pass)
    return Elasticsearch(**kwargs)


def kb_document_v2_mapping(shards: int = 1, replicas: int = 0) -> dict:
    """
    业务功能：生成 kb_document_* v2 目标索引 mapping。
    关键流程：权限过滤字段全部固定为 keyword；保留当前检索依赖的 text/vector/rank_features 字段。
    """

    return {
        "settings": {
            "number_of_shards": shards,
            "number_of_replicas": replicas,
            "analysis": {
                "analyzer": {
                    "ik_smart": {"type": "custom", "tokenizer": "ik_smart"},
                    "ik_max_word": {"type": "custom", "tokenizer": "ik_max_word"},
                }
            },
        },
        "mappings": {
            "dynamic": False,
            "properties": {
                "content": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                "display_content": {"type": "text", "index": False},
                "vector": {
                    "type": "dense_vector",
                    "dims": 1024,
                    "index": True,
                    "similarity": "cosine",
                    "index_options": {"type": "hnsw", "m": 48, "ef_construction": 400},
                },
                "sparse_vector": {"type": "rank_features"},
                "chunk_granularity": {"type": "keyword"},
                "parent_chunk_id": {"type": "keyword"},
                "keywords": {"type": "keyword"},
                "acl_tokens": {"type": "keyword"},
                "source_index": {"type": "keyword"},
                "index_code": {"type": "keyword"},
                "owner_unit_code": {"type": "keyword"},
                "visible_unit_codes": {"type": "keyword"},
                "permission_version": {"type": "long"},
                "metadata": {
                    "dynamic": False,
                    "properties": {
                        "source": {"type": "keyword"},
                        "chunk_id": {"type": "integer"},
                        "is_latest": {"type": "boolean"},
                        "doc_version": {"type": "integer"},
                        "version_at": {"type": "date", "format": "epoch_millis"},
                        "updated_by": {"type": "keyword"},
                        "visibility": {"type": "keyword"},
                        "dept_l2": {"type": "keyword"},
                        "dept_l4": {"type": "keyword"},
                        "dept_l6": {"type": "keyword"},
                        "dept_l9": {"type": "keyword"},
                        "dept_code_full": {"type": "keyword"},
                        "acl_tokens": {"type": "keyword"},
                        "access_groups": {"type": "keyword"},
                        "uploader_id": {"type": "keyword"},
                        "tags_kw": {"type": "keyword"},
                        "tags": {"type": "text"},
                        "quality_score": {"type": "float"},
                        "data_source": {"type": "keyword"},
                        "owner_dept_id": {"type": "keyword"},
                        "source_index": {"type": "keyword"},
                        "index_code": {"type": "keyword"},
                        "owner_unit_code": {"type": "keyword"},
                        "visible_unit_codes": {"type": "keyword"},
                        "permission_version": {"type": "long"},
                        "publish_time": {"type": "date", "format": "yyyy-MM-dd||epoch_millis"},
                        "document_number": {"type": "keyword"},
                        "section_path": {
                            "type": "text",
                            "analyzer": "ik_smart",
                            "fields": {"keyword": {"type": "keyword"}},
                        },
                        "title": {
                            "type": "text",
                            "analyzer": "ik_max_word",
                            "search_analyzer": "ik_smart",
                            "fields": {"keyword": {"type": "keyword"}},
                        },
                        "dynamic_meta": {"type": "object", "dynamic": False},
                        "owner": {"type": "keyword"},
                        "search_queries": {"type": "text"},
                    },
                },
            },
        },
    }


def normalize_list(value, default: Optional[List[str]] = None) -> List[str]:
    """
    业务功能：把历史字段统一为 keyword 数组。
    关键流程：兼容 list/set/tuple/逗号字符串；空值使用默认值，避免权限字段写入 null。
    """

    if value is None:
        return list(default or [])
    if isinstance(value, list):
        result = [str(item).strip() for item in value if str(item).strip()]
    elif isinstance(value, tuple) or isinstance(value, set):
        result = [str(item).strip() for item in value if str(item).strip()]
    elif isinstance(value, str):
        result = [item.strip() for item in value.replace("，", ",").split(",") if item.strip()]
    else:
        text = str(value).strip()
        result = [text] if text else []
    return result or list(default or [])


def normalize_int(value, default: int = 0) -> int:
    """
    业务功能：把历史版本字段统一为整数。
    关键流程：解析失败时回退默认值，避免单条脏数据中断整批迁移。
    """

    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def first_present(*values):
    """
    业务功能：按优先级返回第一个非空值。
    关键流程：迁移字段同时兼容顶层和 metadata，优先保留顶层规范字段。
    """

    for value in values:
        if value is None:
            continue
        if isinstance(value, str) and not value.strip():
            continue
        if isinstance(value, list) and not value:
            continue
        return value
    return None


def build_target_source(source: dict, source_index: str, now_ms: Optional[int] = None) -> dict:
    """
    业务功能：把旧 chunk 文档转换为 v2 白名单结构。
    关键流程：补齐权限根字段和 metadata 镜像字段；缺失 acl_tokens 时写入 _NO_ACCESS，保证默认拒绝。

    注意：permission_version 缺失时回退迁移时刻 now（保持与已迁移索引一致的既有行为）。
    readiness 报告显示 news/public/official 的权限字段覆盖率为 100%，故此默认值实际不会命中真实文档；
    若未来确认 0 更符合投影语义，需同步评估与 kb_doc_meta 的一致性后再调整。
    """

    now = now_ms if now_ms is not None else int(time.time() * 1000)
    metadata = source.get("metadata") if isinstance(source.get("metadata"), dict) else {}
    acl_tokens = normalize_list(first_present(source.get("acl_tokens"), metadata.get("acl_tokens")), ["_NO_ACCESS"])
    visible_unit_codes = normalize_list(
        first_present(source.get("visible_unit_codes"), metadata.get("visible_unit_codes")),
        ["global"],
    )
    resolved_source_index = first_present(source.get("source_index"), metadata.get("source_index"), source_index)
    index_code = first_present(source.get("index_code"), metadata.get("index_code"), resolved_source_index)
    owner_unit_code = first_present(source.get("owner_unit_code"), metadata.get("owner_unit_code"), "global")
    permission_version = normalize_int(
        first_present(source.get("permission_version"), metadata.get("permission_version")),
        now,
    )

    target_metadata = {
        "source": metadata.get("source") or source.get("source") or "",
        "chunk_id": normalize_int(metadata.get("chunk_id"), 0),
        "is_latest": bool(metadata.get("is_latest", True)),
        "doc_version": normalize_int(metadata.get("doc_version"), 0),
        "version_at": metadata.get("version_at"),
        "updated_by": metadata.get("updated_by"),
        "visibility": metadata.get("visibility"),
        "dept_l2": metadata.get("dept_l2"),
        "dept_l4": metadata.get("dept_l4"),
        "dept_l6": metadata.get("dept_l6"),
        "dept_l9": metadata.get("dept_l9"),
        "dept_code_full": metadata.get("dept_code_full"),
        "acl_tokens": acl_tokens,
        "access_groups": normalize_list(metadata.get("access_groups")),
        "uploader_id": metadata.get("uploader_id"),
        "tags_kw": normalize_list(metadata.get("tags_kw")),
        "tags": metadata.get("tags"),
        "quality_score": metadata.get("quality_score"),
        "data_source": metadata.get("data_source"),
        "owner_dept_id": metadata.get("owner_dept_id"),
        "source_index": resolved_source_index,
        "index_code": index_code,
        "owner_unit_code": owner_unit_code,
        "visible_unit_codes": visible_unit_codes,
        "permission_version": permission_version,
        "publish_time": metadata.get("publish_time"),
        "document_number": metadata.get("document_number"),
        "section_path": metadata.get("section_path"),
        "title": metadata.get("title"),
        "dynamic_meta": metadata.get("dynamic_meta") if isinstance(metadata.get("dynamic_meta"), dict) else {},
        "owner": metadata.get("owner"),
        "search_queries": metadata.get("search_queries"),
    }
    target_metadata = {k: v for k, v in target_metadata.items() if v is not None}

    target = {
        "content": source.get("content") or "",
        "display_content": source.get("display_content") or "",
        "chunk_granularity": source.get("chunk_granularity") or "",
        "parent_chunk_id": source.get("parent_chunk_id") or "",
        "keywords": normalize_list(source.get("keywords")),
        "acl_tokens": acl_tokens,
        "source_index": resolved_source_index,
        "index_code": index_code,
        "owner_unit_code": owner_unit_code,
        "visible_unit_codes": visible_unit_codes,
        "permission_version": permission_version,
        "metadata": target_metadata,
    }
    vector = source.get("vector")
    if isinstance(vector, list) and vector:
        target["vector"] = vector
    sparse_vector = source.get("sparse_vector")
    if isinstance(sparse_vector, dict) and sparse_vector:
        target["sparse_vector"] = sparse_vector
    return target


def ensure_target_index(es: Elasticsearch, target_index: str, execute: bool, create_target: bool, shards: int, replicas: int) -> bool:
    """
    业务功能：按需创建 v2 目标索引。
    关键流程：必须显式 --create-target；dry-run 只打印计划，避免迁移脚本误建生产索引。
    """

    if es.indices.exists(index=target_index):
        print(f"[kb_document v2 Migration] target index exists: {target_index}")
        return False
    if not create_target:
        print(f"[kb_document v2 Migration] target index missing and --create-target not set: {target_index}")
        return False
    if not execute:
        print(f"[kb_document v2 Migration] dry-run create index: {target_index}")
        return False
    es.indices.create(index=target_index, body=kb_document_v2_mapping(shards=shards, replicas=replicas))
    print(f"[kb_document v2 Migration] created index: {target_index}")
    return True


def remove_auto_read_alias(
    es: Elasticsearch,
    target_index: str,
    source_index: str,
    read_alias: str,
    execute: bool,
    switch_alias: bool,
) -> List[dict]:
    """
    业务功能：移除 Index Template 自动继承到 v2 目标索引上的读别名。
    关键流程：
      - 未正式切 alias 时，目标索引不能进入线上读别名，避免新旧索引重复召回。
      - 仅在“尚未切换”（source_index 仍持有读别名）时才剥离 target 上的读别名；
        若 source 已不再持有读别名，说明切换早已完成、读别名此时正合法地落在 target 上，
        此时再剥离会把 v2 踢出读路径造成召回中断。重跑安全。
    """

    actions = [{"remove": {"index": target_index, "alias": read_alias}}]
    if switch_alias:
        return []
    # 已切换的标志：source 已不持有读别名。此时绝不剥离 target。
    source_targets = _current_alias_targets(es, read_alias)
    if source_index not in source_targets:
        print(
            f"[kb_document v2 Migration] {read_alias} 已不在 {source_index}（疑似已切换），"
            f"保留 target {target_index} 上的读别名，跳过剥离。"
        )
        return []
    if not execute:
        return actions
    try:
        aliases = es.indices.get_alias(index=target_index, name=read_alias)
    except Exception:
        return []
    if not aliases:
        return []
    es.indices.update_aliases(body={"actions": actions})
    print(f"[kb_document v2 Migration] removed auto alias {read_alias} from {target_index}")
    return actions


def scan_source(es: Elasticsearch, source_index: str, batch_size: int, limit: Optional[int]) -> Iterable[List[dict]]:
    """
    业务功能：流式扫描源 chunk 索引。
    关键流程：使用 scroll 避免 from+size 深分页；limit 用于灰度抽样。
    """

    query = {
        "query": {"match_all": {}},
        "_source": [
            "content",
            "display_content",
            "vector",
            "sparse_vector",
            "chunk_granularity",
            "parent_chunk_id",
            "keywords",
            "acl_tokens",
            "source_index",
            "index_code",
            "owner_unit_code",
            "visible_unit_codes",
            "permission_version",
            "metadata",
        ],
    }
    batch: List[dict] = []
    scanned = 0
    for hit in helpers.scan(es, index=source_index, query=query, size=batch_size, scroll="5m"):
        batch.append(hit)
        scanned += 1
        if len(batch) >= batch_size:
            yield batch
            batch = []
        if limit and scanned >= limit:
            break
    if batch:
        yield batch


def migrate_batch(
    es: Elasticsearch,
    hits: List[dict],
    source_index: str,
    target_index: str,
    execute: bool,
    stats: MigrationStats,
    sample_left: int,
) -> int:
    """
    业务功能：迁移一批 chunk 文档。
    关键流程：dry-run 只计数并打印样例；execute 时使用 bulk index 写入目标索引。
    """

    actions = []
    for hit in hits:
        stats.scanned += 1
        target_source = build_target_source(hit.get("_source") or {}, source_index=hit.get("_index") or source_index)
        if sample_left > 0:
            print(
                "[sample] id={doc_id}, source_index={source_index}, acl_tokens={acl_tokens}, visible_unit_codes={visible}".format(
                    doc_id=hit.get("_id"),
                    source_index=target_source["source_index"],
                    acl_tokens=",".join(target_source["acl_tokens"]),
                    visible=",".join(target_source["visible_unit_codes"]),
                )
            )
            sample_left -= 1
        if execute:
            actions.append(
                {
                    "_op_type": "index",
                    "_index": target_index,
                    "_id": hit.get("_id"),
                    "_source": target_source,
                }
            )
        else:
            stats.dry_run_writes += 1

    if execute and actions:
        success, errors = helpers.bulk(es, actions, raise_on_error=False, refresh=False)
        stats.written += success
        stats.failed += len(errors or [])
        for error in (errors or [])[:5]:
            print(f"[error] bulk index failed: {error}", file=sys.stderr)
    return sample_left


def _current_alias_targets(es: Elasticsearch, alias: str) -> dict:
    """
    业务功能：返回某别名当前指向的 {物理索引: 别名配置}。
    关键流程：别名不存在时返回空 dict，供 switch 幂等判断与“是否已切换”预检使用。
    """

    try:
        refs = es.indices.get_alias(name=alias)
    except Exception:
        return {}
    targets = {}
    for index_name, info in (refs or {}).items():
        targets[index_name] = (info.get("aliases", {}) or {}).get(alias, {}) or {}
    return targets


def switch_aliases(
    es: Elasticsearch,
    source_index: str,
    target_index: str,
    read_alias: str,
    write_alias: str,
    execute: bool,
    switch_alias: bool,
    stats: Optional[MigrationStats] = None,
) -> List[dict]:
    """
    业务功能：可选地把读写 alias 原子切换到 v2。
    关键流程：
      - 必须同时满足 --execute 和 --switch-alias；否则只返回计划，防止误切流量。
      - 数据门禁：本轮存在失败（stats.failed > 0）时禁止切换，避免漏数据进入读路径。
      - 幂等：只在别名确实挂在 source 时 remove、只在未挂在 target 时 add，
        使“已切换后误重跑”不再触发 ES 报错或重复动作。
    """

    if switch_alias and stats is not None and stats.failed > 0:
        print(
            f"[kb_document v2 Migration] ❌ 拒绝切换 alias：本轮 failed={stats.failed}，"
            f"先排查失败原因并重跑迁移，确认 failed=0 后再 --switch-alias。"
        )
        return []

    read_targets = _current_alias_targets(es, read_alias)
    write_targets = _current_alias_targets(es, write_alias)
    actions = []
    if source_index in read_targets:
        actions.append({"remove": {"index": source_index, "alias": read_alias}})
    if source_index in write_targets:
        actions.append({"remove": {"index": source_index, "alias": write_alias}})
    if target_index not in read_targets:
        actions.append({"add": {"index": target_index, "alias": read_alias}})
    if target_index not in write_targets:
        actions.append({"add": {"index": target_index, "alias": write_alias, "is_write_index": True}})

    if not switch_alias:
        if not actions:
            print(f"[kb_document v2 Migration] aliases already on {target_index}, switch is a no-op")
        else:
            print("[kb_document v2 Migration] alias switch skipped")
        return actions
    if not actions:
        print(f"[kb_document v2 Migration] aliases already on {target_index}, nothing to switch")
        return actions
    if not execute:
        print(f"[kb_document v2 Migration] dry-run alias actions: {actions}")
        return actions
    es.indices.update_aliases(body={"actions": actions})
    print(f"[kb_document v2 Migration] aliases switched to {target_index}: {read_alias}, {write_alias}")
    return actions


def build_report(args: argparse.Namespace, stats: MigrationStats, started_at: int, finished_at: int, alias_actions: List[dict]) -> dict:
    """
    业务功能：生成迁移结构化报告。
    关键流程：记录参数、统计和 alias 计划，供离线环境归档审计。
    """

    return {
        "mode": "execute" if args.execute else "dry-run",
        "source_index": args.source_index,
        "target_index": args.target_index,
        "es_host": args.es_host,
        "batch_size": args.batch_size,
        "limit": args.limit,
        "sample": args.sample,
        "create_target": bool(args.create_target),
        "switch_alias": bool(args.switch_alias),
        "read_alias": args.read_alias,
        "write_alias": args.write_alias,
        "started_at": started_at,
        "finished_at": finished_at,
        "stats": asdict(stats),
        "alias_actions": alias_actions,
    }


def parse_args() -> argparse.Namespace:
    """
    业务功能：解析文档 chunk v2 迁移参数。
    关键流程：默认 dry-run；创建、写入、切 alias 都必须显式打开。
    """

    parser = argparse.ArgumentParser(description="Migrate one kb_document_* index to v2 strict permission mapping.")
    parser.add_argument("--source-index", required=True)
    parser.add_argument("--target-index", required=True)
    parser.add_argument("--read-alias", default=DEFAULT_READ_ALIAS)
    parser.add_argument("--write-alias", default="")
    parser.add_argument("--es-host", default=DEFAULT_ES_HOST)
    parser.add_argument("--es-user", default=DEFAULT_ES_USER)
    parser.add_argument("--es-pass", default=DEFAULT_ES_PASS)
    parser.add_argument("--batch-size", type=int, default=200)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--sample", type=int, default=10)
    parser.add_argument("--shards", type=int, default=int(os.getenv("KB_DOCUMENT_SHARDS", "1")))
    parser.add_argument("--replicas", type=int, default=int(os.getenv("KB_DOCUMENT_REPLICAS", "0")))
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--create-target", action="store_true")
    parser.add_argument("--switch-alias", action="store_true")
    parser.add_argument("--output", default="")
    args = parser.parse_args()
    if not args.write_alias:
        args.write_alias = f"{args.source_index}_write"
    return args


def main() -> None:
    """
    业务功能：执行单个 kb_document_* 索引 v2 迁移。
    关键流程：创建目标索引、scroll+bulk 迁移、刷新、可选切 alias，并输出 JSON 报告。
    """

    args = parse_args()
    started_at = int(time.time() * 1000)
    es = create_es_client(args.es_host, args.es_user, args.es_pass)
    stats = MigrationStats()
    limit = args.limit if args.limit > 0 else None
    sample_left = max(args.sample, 0)
    mode = "execute" if args.execute else "dry-run"
    print(
        f"[kb_document v2 Migration] mode={mode}, source={args.source_index}, target={args.target_index}, "
        f"batch_size={args.batch_size}, limit={limit or 'all'}"
    )

    # 预检：读别名是否已指向 target（疑似已完成切换）。仅告警，不阻断——
    # 重跑数据迁移是幂等的，但要让操作者明确知道“这一索引已切换”，避免误判进度。
    read_targets = _current_alias_targets(es, args.read_alias)
    if args.target_index in read_targets and args.source_index not in read_targets:
        print(
            f"[kb_document v2 Migration] ⚠️ {args.read_alias} 已指向 {args.target_index}，"
            f"{args.source_index} 似已切换到 v2。本次为重跑（数据幂等覆盖，alias 切换将为 no-op）。"
        )

    ensure_target_index(es, args.target_index, args.execute, args.create_target, args.shards, args.replicas)
    cleanup_alias_actions = remove_auto_read_alias(
        es,
        target_index=args.target_index,
        source_index=args.source_index,
        read_alias=args.read_alias,
        execute=args.execute,
        switch_alias=args.switch_alias,
    )
    for hits in scan_source(es, args.source_index, args.batch_size, limit):
        sample_left = migrate_batch(es, hits, args.source_index, args.target_index, args.execute, stats, sample_left)

    if args.execute and stats.written > 0:
        es.indices.refresh(index=args.target_index)
    alias_actions = cleanup_alias_actions + switch_aliases(
        es,
        source_index=args.source_index,
        target_index=args.target_index,
        read_alias=args.read_alias,
        write_alias=args.write_alias,
        execute=args.execute,
        switch_alias=args.switch_alias,
        stats=stats,
    )
    finished_at = int(time.time() * 1000)

    report = build_report(args, stats, started_at, finished_at, alias_actions)
    print("[kb_document v2 Migration] done")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.output:
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"[kb_document v2 Migration] report written: {output_path}")


if __name__ == "__main__":
    main()
