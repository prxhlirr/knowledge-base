"""
迁移 kb_qa_pairs 到 kb_qa_pairs_v2。

业务功能：
  创建权限字段类型正确的 QA v2 索引，并将历史 QA 数据通过 scroll + bulk 迁移过去。

关键流程：
  1. 目标索引使用显式 mapping，确保 acl_tokens/source_index/visible_unit_codes 等权限字段为 keyword；
  2. 迁移时只写入 QA 检索需要的白名单字段，避免旧索引中的动态脏字段污染 v2 mapping；
  3. 默认 dry-run，只打印计划；只有显式 --execute 才创建索引和写入数据；
  4. 别名切换必须额外传入 --switch-alias，避免迁移脚本误切生产读写流量。
"""

import argparse
import os
import sys
import time
from dataclasses import dataclass
from typing import Iterable, List, Optional

from dotenv import load_dotenv
from elasticsearch import Elasticsearch, helpers

load_dotenv()

DEFAULT_ES_HOST = os.getenv("ES_HOST", "http://localhost:9200")
DEFAULT_ES_USER = os.getenv("ES_USER", os.getenv("ES_USERNAME", ""))
DEFAULT_ES_PASS = os.getenv("ES_PASS", os.getenv("ES_PASSWORD", ""))
DEFAULT_SOURCE_INDEX = os.getenv("QA_MIGRATION_SOURCE_INDEX", "kb_qa_pairs")
DEFAULT_TARGET_INDEX = os.getenv("QA_MIGRATION_TARGET_INDEX", "kb_qa_pairs_v2")
DEFAULT_READ_ALIAS = os.getenv("QA_INDEX_READ_ALIAS", "kb_qa_read")
DEFAULT_WRITE_ALIAS = os.getenv("QA_INDEX_WRITE_ALIAS", "kb_qa_write")


@dataclass
class MigrationStats:
    """
    业务功能：记录 QA v2 迁移全流程计数。
    关键流程：扫描、预演、写入、失败分开统计，便于迁移后对账。
    """

    scanned: int = 0
    dry_run_writes: int = 0
    written: int = 0
    failed: int = 0


def create_es_client() -> Elasticsearch:
    """
    业务功能：创建 Elasticsearch 客户端。
    关键流程：复用项目通用 ES_HOST/ES_USER/ES_PASS 环境变量，兼容本地和容器环境。
    """

    kwargs = {"hosts": [DEFAULT_ES_HOST]}
    if DEFAULT_ES_USER:
        kwargs["basic_auth"] = (DEFAULT_ES_USER, DEFAULT_ES_PASS)
    return Elasticsearch(**kwargs)


def qa_v2_mapping() -> dict:
    """
    业务功能：生成 kb_qa_pairs_v2 的目标 mapping。
    关键流程：把权限过滤字段固定为 keyword，避免 ES 动态映射成 text+keyword 后导致 terms 查询不命中。
    """

    return {
        "mappings": {
            "dynamic": "strict",
            "properties": {
                "question": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                "question_vector": {
                    "type": "dense_vector",
                    "dims": 1024,
                    "index": True,
                    "similarity": "cosine",
                    "index_options": {"type": "hnsw", "m": 48, "ef_construction": 400},
                },
                "answer_content": {"type": "text"},
                "answer_chunk_id": {"type": "keyword"},
                "doc_hash": {"type": "keyword"},
                "section_path": {"type": "keyword"},
                "source": {"type": "keyword"},
                "acl_tokens": {"type": "keyword"},
                "source_index": {"type": "keyword"},
                "index_code": {"type": "keyword"},
                "owner_unit_code": {"type": "keyword"},
                "visible_unit_codes": {"type": "keyword"},
                "permission_version": {"type": "long"},
                "doc_version": {"type": "integer"},
                "is_latest": {"type": "boolean"},
            },
        }
    }


def normalize_list(value, default: Optional[List[str]] = None) -> List[str]:
    """
    业务功能：将历史字段统一成 keyword 数组。
    关键流程：兼容 list/tuple/set/逗号字符串；空值返回默认值，避免权限字段写入 null。
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
    业务功能：将历史版本字段统一成整数。
    关键流程：解析失败时回退默认值，避免单条脏数据中断整批迁移。
    """

    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def build_target_source(source: dict, now_ms: Optional[int] = None) -> dict:
    """
    业务功能：把旧 QA 文档转换为 v2 白名单结构。
    关键流程：只保留检索和权限需要的字段；缺失 acl_tokens 时写入 _NO_ACCESS，确保迁移后默认拒绝。
    """

    now = now_ms if now_ms is not None else int(time.time() * 1000)
    return {
        "question": source.get("question") or "",
        "question_vector": source.get("question_vector") or source.get("vector") or [],
        "answer_content": source.get("answer_content") or source.get("answer") or source.get("content") or "",
        "answer_chunk_id": source.get("answer_chunk_id") or "",
        "doc_hash": source.get("doc_hash") or "",
        "section_path": source.get("section_path") or "",
        "source": source.get("source") or source.get("owner") or "",
        "acl_tokens": normalize_list(source.get("acl_tokens"), default=["_NO_ACCESS"]),
        "source_index": source.get("source_index") or "",
        "index_code": source.get("index_code") or "",
        "owner_unit_code": source.get("owner_unit_code") or "",
        "visible_unit_codes": normalize_list(source.get("visible_unit_codes"), default=["global"]),
        "permission_version": normalize_int(source.get("permission_version"), default=now),
        "doc_version": normalize_int(source.get("doc_version"), default=0),
        "is_latest": bool(source.get("is_latest", True)),
    }


def ensure_target_index(es: Elasticsearch, target_index: str, execute: bool) -> None:
    """
    业务功能：确保目标 QA v2 索引存在。
    关键流程：dry-run 只打印动作；execute 时索引已存在则跳过，避免重复创建失败。
    """

    exists = es.indices.exists(index=target_index)
    if exists:
        print(f"[QA v2 Migration] target index exists: {target_index}")
        return
    if not execute:
        print(f"[QA v2 Migration] dry-run create index: {target_index}")
        return
    es.indices.create(index=target_index, body=qa_v2_mapping())
    print(f"[QA v2 Migration] created index: {target_index}")


def scan_source(es: Elasticsearch, source_index: str, batch_size: int, limit: Optional[int]) -> Iterable[List[dict]]:
    """
    业务功能：流式扫描历史 QA 文档。
    关键流程：使用 helpers.scan 避免深分页；limit 用于灰度抽样迁移。
    """

    query = {
        "query": {"match_all": {}},
        "_source": [
            "question",
            "question_vector",
            "vector",
            "answer_content",
            "answer",
            "content",
            "answer_chunk_id",
            "doc_hash",
            "section_path",
            "source",
            "owner",
            "acl_tokens",
            "source_index",
            "index_code",
            "owner_unit_code",
            "visible_unit_codes",
            "permission_version",
            "doc_version",
            "is_latest",
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
    target_index: str,
    execute: bool,
    stats: MigrationStats,
    sample_left: int,
) -> int:
    """
    业务功能：迁移一批 QA 文档。
    关键流程：dry-run 只计数和打印样例；execute 时使用 bulk index 写入目标索引。
    """

    actions = []
    for hit in hits:
        stats.scanned += 1
        target_source = build_target_source(hit.get("_source") or {})
        if sample_left > 0:
            print(
                "[sample] qa_id={qa_id}, acl_tokens={acl_tokens}, source_index={source_index}, "
                "visible_unit_codes={visible_unit_codes}".format(
                    qa_id=hit.get("_id"),
                    acl_tokens=",".join(target_source["acl_tokens"]),
                    source_index=target_source["source_index"],
                    visible_unit_codes=",".join(target_source["visible_unit_codes"]),
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
    关键流程：别名不存在时返回空 dict，供 switch 幂等判断使用。
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
) -> None:
    """
    业务功能：可选地把 QA 读写别名切换到 v2。
    关键流程：
      - 必须同时满足 --execute 和 --switch-alias；否则只打印计划，防止误切生产流量。
      - 动作幂等：只在别名当前确实挂在 source 时 remove，只在未挂在 target 时 add，
        避免对已切换环境重跑时报 ES 8.6.2 的 unknown field / 重复动作错误。
      - 不使用 ignore_unavailable：该字段在 ES 8.6.2 的 _aliases remove 动作里不被支持，
        会直接 400（见 20260703_qa_pairs_v2_alias_cutover_report.md §3）。
    """

    actions = []
    # 仅移除当前确实挂在 source 上的别名，避免重跑时报 "alias is missing"
    read_targets = _current_alias_targets(es, read_alias)
    write_targets = _current_alias_targets(es, write_alias)
    if source_index in read_targets:
        actions.append({"remove": {"index": source_index, "alias": read_alias}})
    if source_index in write_targets:
        actions.append({"remove": {"index": source_index, "alias": write_alias}})
    if target_index not in read_targets:
        actions.append({"add": {"index": target_index, "alias": read_alias}})
    if target_index not in write_targets:
        actions.append({"add": {"index": target_index, "alias": write_alias, "is_write_index": True}})

    if not switch_alias:
        print("[QA v2 Migration] alias switch skipped")
        return
    if not actions:
        print(f"[QA v2 Migration] aliases already on {target_index}, nothing to switch")
        return
    if not execute:
        print(f"[QA v2 Migration] dry-run alias actions: {actions}")
        return
    es.indices.update_aliases(body={"actions": actions})
    print(f"[QA v2 Migration] aliases switched to {target_index}: {read_alias}, {write_alias}")


def parse_args() -> argparse.Namespace:
    """
    业务功能：解析迁移参数。
    关键流程：默认 dry-run；写入和切别名都需要显式开关。
    """

    parser = argparse.ArgumentParser(description="Migrate kb_qa_pairs to kb_qa_pairs_v2 with strict permission mapping.")
    parser.add_argument("--source-index", default=DEFAULT_SOURCE_INDEX)
    parser.add_argument("--target-index", default=DEFAULT_TARGET_INDEX)
    parser.add_argument("--read-alias", default=DEFAULT_READ_ALIAS)
    parser.add_argument("--write-alias", default=DEFAULT_WRITE_ALIAS)
    parser.add_argument("--batch-size", type=int, default=200)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--sample", type=int, default=10)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--switch-alias", action="store_true")
    return parser.parse_args()


def main() -> None:
    """
    业务功能：执行 QA v2 迁移。
    关键流程：创建目标索引、批量迁移、刷新、可选切别名，并输出对账统计。
    """

    args = parse_args()
    es = create_es_client()
    stats = MigrationStats()
    limit = args.limit if args.limit > 0 else None
    sample_left = max(args.sample, 0)
    mode = "execute" if args.execute else "dry-run"
    print(
        f"[QA v2 Migration] mode={mode}, source={args.source_index}, target={args.target_index}, "
        f"batch_size={args.batch_size}, limit={limit or 'all'}"
    )

    ensure_target_index(es, args.target_index, args.execute)
    for hits in scan_source(es, args.source_index, args.batch_size, limit):
        sample_left = migrate_batch(es, hits, args.target_index, args.execute, stats, sample_left)

    if args.execute and stats.written > 0:
        es.indices.refresh(index=args.target_index)
    switch_aliases(
        es,
        source_index=args.source_index,
        target_index=args.target_index,
        read_alias=args.read_alias,
        write_alias=args.write_alias,
        execute=args.execute,
        switch_alias=args.switch_alias,
    )

    print("[QA v2 Migration] done")
    print(f"  scanned={stats.scanned}")
    print(f"  dry_run_writes={stats.dry_run_writes}")
    print(f"  written={stats.written}")
    print(f"  failed={stats.failed}")


if __name__ == "__main__":
    main()
