#!/usr/bin/env python3
"""
Apply MySQL-authoritative permission projection snapshots to Elasticsearch.

输入 JSONL 每行示例：
{"source_name":"a.docx","target_index":"kb_document_v1_v2","acl_tokens":["_INTERNAL"],"owner_unit_code":"global","visible_unit_codes":["global"],"permission_version":1783520000000}
"""

import argparse
import json
import os
import time
from dataclasses import dataclass
from typing import Any, Dict, Iterable, List, Optional

from elasticsearch import Elasticsearch


DEFAULT_ES_HOST = os.getenv("ES_HOST", "http://localhost:9200")
DEFAULT_ES_USER = os.getenv("ES_USER", os.getenv("ES_USERNAME", ""))
DEFAULT_ES_PASS = os.getenv("ES_PASS", os.getenv("ES_PASSWORD", ""))


@dataclass
class ApplyStats:
    read: int = 0
    skipped: int = 0
    updated_sources: int = 0
    chunk_updates: int = 0
    aux_updates: int = 0
    errors: int = 0


def parse_args() -> argparse.Namespace:
    """
    业务功能：解析 MySQL 权限快照应用脚本参数。
    关键流程：所有连接参数均支持环境变量覆盖，避免把离线环境地址和密码写死在脚本中。
    """

    parser = argparse.ArgumentParser(
        description="Apply MySQL permission snapshot JSONL to ES v2 indexes."
    )
    parser.add_argument("--input", required=True, help="MySQL 导出的 JSONL 权限快照路径")
    parser.add_argument("--es-host", default=DEFAULT_ES_HOST, help="ES 地址")
    parser.add_argument("--es-user", default=DEFAULT_ES_USER, help="ES Basic Auth 用户名")
    parser.add_argument("--es-pass", default=DEFAULT_ES_PASS, help="ES Basic Auth 密码")
    parser.add_argument("--chunk-index", default=os.getenv("KB_DOCUMENT_READ_ALIAS", "kb_document"),
                        help="chunk 读别名或目标索引，默认 kb_document")
    parser.add_argument("--aux-indexes", default="kb_doc_meta_v2,kb_doc_search_v1,kb_qa_pairs_v2",
                        help="逗号分隔的辅助索引或写别名，按 source 字段更新")
    parser.add_argument("--limit", type=int, default=0, help="最多处理记录数，0 表示不限")
    parser.add_argument("--sample", type=int, default=10, help="打印样例数量")
    parser.add_argument("--execute", action="store_true", help="真实写回 ES；未设置时只 dry-run")
    parser.add_argument("--output", default="", help="输出 JSON 报告路径")
    return parser.parse_args()


def create_es_client(es_host: str, es_user: str, es_pass: str) -> Elasticsearch:
    """
    业务功能：创建 ES 客户端。
    设计原因：迁移脚本必须兼容带 Basic Auth 的离线 ES，不能依赖默认匿名访问。
    """

    kwargs: Dict[str, Any] = {"hosts": [es_host], "request_timeout": 300}
    if es_user:
        kwargs["basic_auth"] = (es_user, es_pass)
    return Elasticsearch(**kwargs)


def as_list(value: Any) -> List[str]:
    """
    业务功能：把 JSON 数组、逗号分隔字符串统一转换为去重后的字符串列表。
    设计原因：MySQL 导出方式可能是 JSON_ARRAYAGG 或 GROUP_CONCAT，脚本需要兼容两种安全格式。
    """

    if value is None:
        return []
    if isinstance(value, list):
        raw = value
    elif isinstance(value, str):
        text = value.strip()
        if not text:
            return []
        if text.startswith("["):
            try:
                raw = json.loads(text)
            except Exception:
                raw = text.split(",")
        else:
            raw = text.split(",")
    else:
        raw = [value]

    result: List[str] = []
    seen = set()
    for item in raw:
        token = str(item).strip()
        if token and token not in seen:
            seen.add(token)
            result.append(token)
    return result


def iter_snapshot(path: str, limit: int) -> Iterable[Dict[str, Any]]:
    """
    业务功能：逐行读取 JSONL 权限快照。
    关键流程：遇到空行跳过；遇到非法 JSON 立即抛错，避免静默漏迁权限。
    """

    count = 0
    with open(path, "r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            text = line.strip()
            if not text:
                continue
            try:
                item = json.loads(text)
            except Exception as exc:
                raise ValueError(f"invalid JSON at line {line_no}: {exc}") from exc
            yield item
            count += 1
            if limit and count >= limit:
                break


def normalize_record(raw: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """
    业务功能：标准化单条权限投影记录。
    关键流程：source 缺失直接跳过；ACL token 缺失时强制 `_NO_ACCESS`，保证 fail-closed。
    """

    source_name = str(raw.get("source_name") or raw.get("source") or "").strip()
    if not source_name:
        return None
    acl_tokens = as_list(raw.get("acl_tokens")) or ["_NO_ACCESS"]
    owner_unit_code = str(raw.get("owner_unit_code") or raw.get("dept_code") or "global").strip() or "global"
    visible_unit_codes = as_list(raw.get("visible_unit_codes")) or [owner_unit_code]
    permission_version = raw.get("permission_version")
    try:
        permission_version = int(permission_version)
    except Exception:
        permission_version = int(time.time() * 1000)

    return {
        "source_name": source_name,
        "target_index": str(raw.get("target_index") or "").strip(),
        "acl_tokens": acl_tokens,
        "owner_unit_code": owner_unit_code,
        "visible_unit_codes": visible_unit_codes,
        "permission_version": permission_version,
    }


def chunk_script() -> str:
    """
    业务功能：生成 chunk 索引权限覆盖脚本。
    设计原因：chunk 索引同时存在顶层字段和 metadata 字段，必须双写保持检索与展示一致。
    """

    return (
        "ctx._source.acl_tokens = params.aclTokens; "
        "ctx._source.owner_unit_code = params.ownerUnitCode; "
        "ctx._source.visible_unit_codes = params.visibleUnitCodes; "
        "ctx._source.permission_version = params.permissionVersion; "
        "if (ctx._source.metadata != null) { "
        "  ctx._source.metadata.acl_tokens = params.aclTokens; "
        "  ctx._source.metadata.owner_unit_code = params.ownerUnitCode; "
        "  ctx._source.metadata.visible_unit_codes = params.visibleUnitCodes; "
        "  ctx._source.metadata.permission_version = params.permissionVersion; "
        "}"
    )


def top_level_script() -> str:
    """
    业务功能：生成辅助索引权限覆盖脚本。
    设计原因：doc_meta/doc_search/QA 使用顶层 source 和权限字段，无 metadata 嵌套结构。
    """

    return (
        "ctx._source.acl_tokens = params.aclTokens; "
        "ctx._source.owner_unit_code = params.ownerUnitCode; "
        "ctx._source.visible_unit_codes = params.visibleUnitCodes; "
        "ctx._source.permission_version = params.permissionVersion;"
    )


def update_by_source(es: Elasticsearch, index: str, source_field: str, script: str,
                     record: Dict[str, Any], execute: bool) -> int:
    """
    业务功能：按 source 名称覆盖单个索引中的权限投影字段。
    关键流程：dry-run 时只返回 0；execute 时使用 update_by_query 并允许版本冲突继续处理。
    """

    if not index:
        return 0
    if not execute:
        return 0
    response = es.update_by_query(
        index=index,
        query={"term": {source_field: record["source_name"]}},
        script={
            "lang": "painless",
            "source": script,
            "params": {
                "aclTokens": record["acl_tokens"],
                "ownerUnitCode": record["owner_unit_code"],
                "visibleUnitCodes": record["visible_unit_codes"],
                "permissionVersion": record["permission_version"],
            },
        },
        conflicts="proceed",
        refresh=True,
        request_timeout=300,
    )
    return int(response.get("updated", 0))


def run() -> int:
    """
    业务功能：应用 MySQL 权限快照到 chunk/doc_meta/doc_search/QA 目标索引。
    关键流程：读取 JSONL → 标准化权限字段 → 先更新 chunk → 再更新辅助索引 → 输出结构化报告。
    """

    args = parse_args()
    es = create_es_client(args.es_host, args.es_user, args.es_pass)
    aux_indexes = [item.strip() for item in args.aux_indexes.split(",") if item.strip()]
    stats = ApplyStats()
    samples: List[Dict[str, Any]] = []
    errors: List[Dict[str, Any]] = []

    for raw in iter_snapshot(args.input, args.limit):
        stats.read += 1
        record = normalize_record(raw)
        if record is None:
            stats.skipped += 1
            continue
        if len(samples) < args.sample:
            samples.append(record)
        try:
            chunk_index = record["target_index"] or args.chunk_index
            stats.chunk_updates += update_by_source(
                es, chunk_index, "metadata.source", chunk_script(), record, args.execute
            )
            for aux_index in aux_indexes:
                stats.aux_updates += update_by_source(
                    es, aux_index, "source", top_level_script(), record, args.execute
                )
            stats.updated_sources += 1
        except Exception as exc:
            stats.errors += 1
            errors.append({"source_name": record["source_name"], "error": str(exc)})

    report = {
        "mode": "execute" if args.execute else "dry-run",
        "input": args.input,
        "chunk_index": args.chunk_index,
        "aux_indexes": aux_indexes,
        "stats": stats.__dict__,
        "samples": samples,
        "errors": errors[:50],
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.output:
        with open(args.output, "w", encoding="utf-8") as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2)
    return 1 if stats.errors else 0


if __name__ == "__main__":
    raise SystemExit(run())
