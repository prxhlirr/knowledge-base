"""
历史 QA 权限字段回填脚本。

业务功能：
  为 kb_qa_pairs / kb_qa_read 中缺少 source_index 的历史 QA 文档补齐权限投影字段，
  使 QA 检索可以按用户可读的 kb_document_* 物理索引进行预过滤。

关键流程：
  1. 扫描 QA 读索引中缺少 source_index 的文档；
  2. 优先根据 answer_chunk_id 反查 kb_document 读别名下的真实物理索引；
  3. answer_chunk_id 找不到时，再根据 source 查询一个最新 chunk 作为兜底；
  4. 仅在显式传入 --execute 时批量写回，默认 dry-run 防止误改历史数据。

执行示例：
  python ai_service/scripts/backfill_qa_source_index.py
  python ai_service/scripts/backfill_qa_source_index.py --limit 100
  python ai_service/scripts/backfill_qa_source_index.py --execute --batch-size 500
"""

import argparse
import os
import sys
import time
from dataclasses import dataclass
from typing import Dict, Iterable, List, Optional

from dotenv import load_dotenv
from elasticsearch import Elasticsearch, helpers

load_dotenv()

DEFAULT_ES_HOST = os.getenv("ES_HOST", "http://localhost:9200")
DEFAULT_ES_USER = os.getenv("ES_USER", os.getenv("ES_USERNAME", ""))
DEFAULT_ES_PASS = os.getenv("ES_PASS", os.getenv("ES_PASSWORD", ""))
DEFAULT_QA_INDEX = os.getenv("QA_INDEX_READ_ALIAS", os.getenv("QA_INDEX_PATTERN", "kb_qa_read"))
DEFAULT_CHUNK_INDEX = os.getenv("KB_DOCUMENT_READ_ALIAS", "kb_document")


@dataclass
class ChunkProjection:
    """
    业务功能：承载从真实 chunk 反查出来的权限投影。

    关键流程：source_index 来自 ES hit._index，它是历史 QA 权限过滤的事实来源；
    单位字段尽量复用 chunk.metadata，避免在 QA 回填阶段重新推导组织树。
    """

    source_index: str
    owner_unit_code: str
    visible_unit_codes: List[str]


@dataclass
class BackfillStats:
    """
    业务功能：记录本次回填的完整结果，便于 dry-run 和正式执行后对账。

    关键流程：所有跳过、失败、成功都显式计数，避免批量脚本静默吞掉异常数据。
    """

    scanned: int = 0
    resolved_by_chunk_id: int = 0
    resolved_by_source: int = 0
    skipped_without_key: int = 0
    skipped_unresolved: int = 0
    dry_run_updates: int = 0
    written: int = 0
    failed: int = 0


def create_es_client() -> Elasticsearch:
    """
    业务功能：创建 ES 客户端。

    关键流程：沿用项目现有环境变量命名，支持本地和容器环境共用同一个脚本。
    """

    kwargs = {"hosts": [DEFAULT_ES_HOST]}
    if DEFAULT_ES_USER:
        kwargs["basic_auth"] = (DEFAULT_ES_USER, DEFAULT_ES_PASS)
    return Elasticsearch(**kwargs)


def index_code(source_index: str) -> str:
    """
    业务功能：从 kb_document_* 物理索引名提取业务索引编码。

    关键流程：和在线写入逻辑保持一致，避免历史 QA 与新 QA 的 index_code 语义分叉。
    """

    if source_index.startswith("kb_document_"):
        return source_index[len("kb_document_") :]
    return source_index


def normalize_codes(value) -> List[str]:
    """
    业务功能：把历史数据中可能存在的字符串、数组、空值统一整理为单位编码列表。

    关键流程：脚本只做类型归一化，不在这里推导上级单位，避免离线回填覆盖组织权限规则。
    """

    if value is None:
        return []
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    if isinstance(value, tuple) or isinstance(value, set):
        return [str(item).strip() for item in value if str(item).strip()]
    if isinstance(value, str):
        return [item.strip() for item in value.split(",") if item.strip()]
    return [str(value).strip()] if str(value).strip() else []


def projection_from_chunk_hit(hit: dict) -> ChunkProjection:
    """
    业务功能：从 chunk 查询结果提取 QA 需要的权限投影。

    关键流程：source_index 必须取 hit._index；单位字段从 metadata 中读取，兼容当前代码里的
    owner_dept_id / visible_depts 命名和新索引中的 owner_unit_code / visible_unit_codes 命名。
    """

    source = hit.get("_source") or {}
    metadata = source.get("metadata") or {}
    owner_unit_code = (
        source.get("owner_unit_code")
        or metadata.get("owner_unit_code")
        or metadata.get("owner_dept_id")
        or source.get("owner_dept_id")
        or ""
    )
    visible_unit_codes = (
        normalize_codes(source.get("visible_unit_codes"))
        or normalize_codes(metadata.get("visible_unit_codes"))
        or normalize_codes(metadata.get("visible_depts"))
        or normalize_codes(owner_unit_code)
    )
    return ChunkProjection(
        source_index=hit.get("_index") or "",
        owner_unit_code=str(owner_unit_code).strip(),
        visible_unit_codes=visible_unit_codes,
    )


def missing_source_index_query() -> dict:
    """
    业务功能：定位需要回填的 QA 文档。

    关键流程：只处理字段缺失的历史数据；已经存在 source_index 的文档由在线链路负责，不做覆盖。
    """

    return {
        "query": {
            "bool": {
                "should": [
                    {"bool": {"must_not": [{"exists": {"field": "source_index"}}]}},
                    {"term": {"source_index": ""}},
                ],
                "minimum_should_match": 1,
            }
        },
        "_source": [
            "question",
            "source",
            "answer_chunk_id",
            "doc_hash",
            "doc_version",
            "source_index",
        ],
    }


def scan_qa_docs(
    es: Elasticsearch,
    qa_index: str,
    batch_size: int,
    limit: Optional[int],
) -> Iterable[List[dict]]:
    """
    业务功能：按批流式读取缺少 source_index 的 QA 文档。

    关键流程：使用 helpers.scan 避免深分页；limit 只用于灰度验证和测试抽样。
    """

    batch: List[dict] = []
    scanned = 0
    for hit in helpers.scan(
        es,
        index=qa_index,
        query=missing_source_index_query(),
        size=batch_size,
        preserve_order=False,
        scroll="5m",
    ):
        batch.append(hit)
        scanned += 1
        if len(batch) >= batch_size:
            yield batch
            batch = []
        if limit and scanned >= limit:
            break
    if batch:
        yield batch


def resolve_by_chunk_id(
    es: Elasticsearch,
    chunk_index: str,
    qa_hits: List[dict],
) -> Dict[str, ChunkProjection]:
    """
    业务功能：根据 QA.answer_chunk_id 批量反查 chunk 的真实物理索引。

    关键流程：answer_chunk_id 是 QA 写入时与 fine chunk 对齐的稳定键，优先级最高。
    """

    chunk_ids = []
    for hit in qa_hits:
        answer_chunk_id = ((hit.get("_source") or {}).get("answer_chunk_id") or "").strip()
        if answer_chunk_id:
            chunk_ids.append(answer_chunk_id)
    if not chunk_ids:
        return {}

    response = es.mget(
        index=chunk_index,
        body={"ids": sorted(set(chunk_ids))},
        _source_includes=[
            "metadata.owner_dept_id",
            "metadata.owner_unit_code",
            "metadata.visible_depts",
            "metadata.visible_unit_codes",
            "owner_dept_id",
            "owner_unit_code",
            "visible_unit_codes",
        ],
    )
    projections: Dict[str, ChunkProjection] = {}
    for doc in response.get("docs", []):
        if doc.get("found") and doc.get("_index"):
            projections[doc.get("_id")] = projection_from_chunk_hit(doc)
    return projections


def resolve_by_source(
    es: Elasticsearch,
    chunk_index: str,
    source_name: str,
) -> Optional[ChunkProjection]:
    """
    业务功能：当 answer_chunk_id 无法命中时，根据 source 查找一个最新 chunk 兜底。

    关键流程：source 的唯一性弱于 chunk_id，因此只作为历史脏数据的补救路径。
    """

    if not source_name:
        return None
    body = {
        "size": 1,
        "_source": [
            "metadata.owner_dept_id",
            "metadata.owner_unit_code",
            "metadata.visible_depts",
            "metadata.visible_unit_codes",
            "owner_dept_id",
            "owner_unit_code",
            "visible_unit_codes",
        ],
        "query": {
            "bool": {
                "must": [{"term": {"metadata.source": source_name}}],
                "filter": [
                    {
                        "bool": {
                            "should": [
                                {"term": {"metadata.is_latest": True}},
                                {"term": {"is_latest": True}},
                                {"bool": {"must_not": [{"exists": {"field": "metadata.is_latest"}}]}},
                            ],
                            "minimum_should_match": 1,
                        }
                    }
                ],
            }
        },
    }
    response = es.search(index=chunk_index, body=body)
    hits = response.get("hits", {}).get("hits", [])
    if not hits:
        return None
    return projection_from_chunk_hit(hits[0])


def build_update_doc(projection: ChunkProjection) -> dict:
    """
    业务功能：构造写回 QA 的最小权限字段集合。

    关键流程：只补权限过滤必需字段，不触碰 question/answer/vector 等业务内容字段。
    """

    now_ms = int(time.time() * 1000)
    return {
        "source_index": projection.source_index,
        "index_code": index_code(projection.source_index),
        "owner_unit_code": projection.owner_unit_code,
        "visible_unit_codes": projection.visible_unit_codes,
        "permission_version": now_ms,
    }


def process_batch(
    es: Elasticsearch,
    qa_hits: List[dict],
    qa_index: str,
    chunk_index: str,
    execute: bool,
    sample_left: int,
    stats: BackfillStats,
) -> int:
    """
    业务功能：处理一批 QA 文档，完成解析、预演打印和可选批量写回。

    关键流程：写回目标使用 QA hit._index，确保通过读别名扫描到的物理索引能被原地更新。
    """

    id_projections = resolve_by_chunk_id(es, chunk_index, qa_hits)
    actions = []
    source_cache: Dict[str, Optional[ChunkProjection]] = {}

    for hit in qa_hits:
        stats.scanned += 1
        qa_source = hit.get("_source") or {}
        answer_chunk_id = (qa_source.get("answer_chunk_id") or "").strip()
        source_name = (qa_source.get("source") or "").strip()
        projection = None

        if answer_chunk_id and answer_chunk_id in id_projections:
            projection = id_projections[answer_chunk_id]
            stats.resolved_by_chunk_id += 1
        elif source_name:
            if source_name not in source_cache:
                source_cache[source_name] = resolve_by_source(es, chunk_index, source_name)
            projection = source_cache[source_name]
            if projection:
                stats.resolved_by_source += 1
        else:
            stats.skipped_without_key += 1

        if not projection or not projection.source_index:
            stats.skipped_unresolved += 1
            continue

        update_doc = build_update_doc(projection)
        if sample_left > 0:
            print(
                "[sample] qa_id={qa_id}, qa_index={qa_index}, source_index={source_index}, "
                "index_code={index_code}, owner_unit_code={owner_unit_code}, visible_unit_codes={visible_unit_codes}".format(
                    qa_id=hit.get("_id"),
                    qa_index=hit.get("_index") or qa_index,
                    source_index=update_doc["source_index"],
                    index_code=update_doc["index_code"],
                    owner_unit_code=update_doc["owner_unit_code"],
                    visible_unit_codes=",".join(update_doc["visible_unit_codes"]),
                )
            )
            sample_left -= 1

        if execute:
            actions.append(
                {
                    "_op_type": "update",
                    "_index": hit.get("_index") or qa_index,
                    "_id": hit.get("_id"),
                    "doc": update_doc,
                }
            )
        else:
            stats.dry_run_updates += 1

    if execute and actions:
        success, errors = helpers.bulk(es, actions, raise_on_error=False, refresh=False)
        stats.written += success
        stats.failed += len(errors or [])
        for error in (errors or [])[:5]:
            print(f"[error] bulk update failed: {error}", file=sys.stderr)

    return sample_left


def parse_args() -> argparse.Namespace:
    """
    业务功能：解析命令行参数。

    关键流程：默认 dry-run，正式写入必须显式 --execute，降低批量运维误操作风险。
    """

    parser = argparse.ArgumentParser(description="Backfill source_index for historical QA documents.")
    parser.add_argument("--qa-index", default=DEFAULT_QA_INDEX, help="QA 读索引或别名，默认 kb_qa_read")
    parser.add_argument("--chunk-index", default=DEFAULT_CHUNK_INDEX, help="chunk 读索引或别名，默认 kb_document")
    parser.add_argument("--batch-size", type=int, default=200, help="每批处理数量")
    parser.add_argument("--limit", type=int, default=0, help="最多扫描数量，0 表示不限制")
    parser.add_argument("--sample", type=int, default=10, help="预览样例数量")
    parser.add_argument("--execute", action="store_true", help="真实写回 ES；未设置时只预演")
    return parser.parse_args()


def main() -> None:
    """
    业务功能：执行历史 QA source_index 回填。

    关键流程：先输出运行参数，再流式处理，最后打印对账统计。
    """

    args = parse_args()
    es = create_es_client()
    stats = BackfillStats()
    sample_left = max(args.sample, 0)
    mode = "execute" if args.execute else "dry-run"
    limit = args.limit if args.limit > 0 else None

    print(
        f"[QA SourceIndex Backfill] mode={mode}, qa_index={args.qa_index}, "
        f"chunk_index={args.chunk_index}, batch_size={args.batch_size}, limit={limit or 'all'}"
    )

    for qa_hits in scan_qa_docs(es, args.qa_index, args.batch_size, limit):
        sample_left = process_batch(
            es=es,
            qa_hits=qa_hits,
            qa_index=args.qa_index,
            chunk_index=args.chunk_index,
            execute=args.execute,
            sample_left=sample_left,
            stats=stats,
        )

    if args.execute and stats.written > 0:
        es.indices.refresh(index=args.qa_index)

    print("[QA SourceIndex Backfill] done")
    print(f"  scanned={stats.scanned}")
    print(f"  resolved_by_chunk_id={stats.resolved_by_chunk_id}")
    print(f"  resolved_by_source={stats.resolved_by_source}")
    print(f"  skipped_without_key={stats.skipped_without_key}")
    print(f"  skipped_unresolved={stats.skipped_unresolved}")
    print(f"  dry_run_updates={stats.dry_run_updates}")
    print(f"  written={stats.written}")
    print(f"  failed={stats.failed}")


if __name__ == "__main__":
    main()
