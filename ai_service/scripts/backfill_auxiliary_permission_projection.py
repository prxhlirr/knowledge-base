#!/usr/bin/env python
"""
业务功能：为 kb_doc_search / kb_doc_meta 等文档级辅助索引补齐权限投影字段。
关键流程：
1. 扫描辅助索引中缺少 source_index 或 visible_unit_codes 的 latest 文档；
2. 使用 source/source_name 到 kb_document 读别名反查最新 chunk；
3. 从 chunk 的真实 _index 与单位字段生成最小权限投影；
4. 默认 dry-run，只有显式 --execute 才写回 ES，且写回脚本只填空字段。

设计原因：辅助索引是检索性能优化层，历史字段缺失会迫使检索走兼容分支；
        但重建整个辅助索引成本高、风险大，因此这里仅修复权限过滤必需字段。
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
DEFAULT_AUX_INDEXES = os.getenv("AUX_PERMISSION_BACKFILL_INDEXES", "kb_doc_search,kb_doc_meta")
DEFAULT_CHUNK_INDEX = os.getenv("KB_DOCUMENT_READ_ALIAS", "kb_document")


@dataclass
class ChunkProjection:
    """
    业务功能：承载从 chunk 事实源反查得到的辅助索引权限投影。
    关键流程：source_index 必须来自 chunk hit._index；单位字段只复用已入库字段，不在脚本内重建组织树。
    设计原因：离线补齐应以现有 ES 事实为准，避免脚本与 Java 权限域重复维护单位层级规则。
    """

    source_index: str
    owner_unit_code: str
    visible_unit_codes: List[str]


@dataclass
class BackfillStats:
    """
    业务功能：记录辅助索引权限投影补齐结果。
    关键流程：区分可解析、无 source、未解析、dry-run、写入和失败，便于执行后对账。
    设计原因：权限补齐脚本必须可审计，不能把数据质量问题隐藏在总数里。
    """

    scanned: int = 0
    resolved_by_source: int = 0
    skipped_without_source: int = 0
    skipped_unresolved: int = 0
    dry_run_updates: int = 0
    written: int = 0
    failed: int = 0


def create_es_client() -> Elasticsearch:
    """
    业务功能：创建 ES 客户端。
    关键流程：沿用项目现有 ES_HOST/ES_USER/ES_PASS 环境变量，支持本地和带 Basic Auth 的环境。
    设计原因：运维脚本不应硬编码连接参数，避免不同环境执行时修改代码。
    """

    kwargs = {"hosts": [DEFAULT_ES_HOST]}
    if DEFAULT_ES_USER:
        kwargs["basic_auth"] = (DEFAULT_ES_USER, DEFAULT_ES_PASS)
    return Elasticsearch(**kwargs)


def index_code(source_index: str) -> str:
    """
    业务功能：从 kb_document_* 物理索引名提取业务索引编码。
    关键流程：和在线写入链路保持一致，非标准索引名原样返回。
    设计原因：角色索引授权依赖 index_code/source_index 语义一致，历史数据不能另起规则。
    """

    source_index = (source_index or "").strip()
    prefix = "kb_document_"
    if source_index.startswith(prefix):
        return source_index[len(prefix) :]
    return source_index


def normalize_codes(value) -> List[str]:
    """
    业务功能：把字符串、数组、集合等历史形态统一为去重后的单位编码列表。
    关键流程：只做类型归一和去空，不在脚本中推导上级单位。
    设计原因：单位可见链应由入库或 Java 权限域产出，离线脚本只搬运已证明的投影。
    """

    if value is None:
        return []
    if isinstance(value, (list, tuple, set)):
        raw = value
    elif isinstance(value, str):
        raw = value.split(",")
    else:
        raw = [value]
    result = []
    for item in raw:
        text = str(item).strip()
        if text and text not in result:
            result.append(text)
    return result


def latest_filter(field: str) -> dict:
    """
    业务功能：构造 latest 文档过滤条件。
    关键流程：字段为 true 或字段缺失都纳入扫描，兼容历史辅助索引没有 is_latest 的情况。
    设计原因：历史数据没有 latest 字段时仍可能参与线上检索，不能在补齐阶段漏掉。
    """

    return {
        "bool": {
            "should": [
                {"term": {field: True}},
                {"bool": {"must_not": [{"exists": {"field": field}}]}},
            ],
            "minimum_should_match": 1,
        }
    }


def missing_projection_query() -> dict:
    """
    业务功能：定位辅助索引中缺少权限投影字段的文档。
    关键流程：只扫描 source_index 或 visible_unit_codes 缺失/空值的文档，避免触碰健康数据。
    设计原因：补齐脚本的影响面越小越安全，现有正确投影应交给在线链路维护。
    """

    return {
        "query": {
            "bool": {
                "filter": [latest_filter("is_latest")],
                "should": [
                    {"bool": {"must_not": [{"exists": {"field": "source_index"}}]}},
                    {"term": {"source_index": ""}},
                    {"bool": {"must_not": [{"exists": {"field": "visible_unit_codes"}}]}},
                ],
                "minimum_should_match": 1,
            }
        },
        "_source": ["source", "source_name", "doc_id", "source_index", "visible_unit_codes"],
    }


def scan_aux_docs(
    es: Elasticsearch,
    aux_index: str,
    batch_size: int,
    limit: Optional[int],
) -> Iterable[List[dict]]:
    """
    业务功能：按批流式读取待补齐的辅助索引文档。
    关键流程：使用 helpers.scan 避免深分页；limit 仅用于灰度抽样，不改变查询语义。
    设计原因：辅助索引可能较大，扫描必须具备稳定内存占用。
    """

    batch: List[dict] = []
    scanned = 0
    for hit in helpers.scan(
        es,
        index=aux_index,
        query=missing_projection_query(),
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


def source_projection_query(source_name: str) -> dict:
    """
    业务功能：定位某个 source 在辅助索引中的文档级记录。
    关键流程：同时匹配 source/source_name 的 keyword 与普通字段，并限制 latest 文档。
    设计原因：在线辅助索引同步失败后，补偿任务只应修复失败文档本身，避免全索引扫描。
    """

    source_name = (source_name or "").strip()
    return {
        "query": {
            "bool": {
                "filter": [latest_filter("is_latest")],
                "should": [
                    {"term": {"source.keyword": source_name}},
                    {"term": {"source": source_name}},
                    {"term": {"source_name.keyword": source_name}},
                    {"term": {"source_name": source_name}},
                ],
                "minimum_should_match": 1,
            }
        },
        "_source": ["source", "source_name", "doc_id", "source_index", "visible_unit_codes"],
    }


def scan_aux_docs_by_source(
    es: Elasticsearch,
    aux_index: str,
    source_name: str,
    batch_size: int,
) -> Iterable[List[dict]]:
    """
    业务功能：按 source 小范围扫描辅助索引记录，供在线失败补偿调用。
    关键流程：复用 helpers.scan，避免 source 存在多版本或多数据源记录时遗漏。
    设计原因：补偿任务来自单文档失败事件，应以文档键定向修复，而不是触发全量巡检。
    """

    batch: List[dict] = []
    for hit in helpers.scan(
        es,
        index=aux_index,
        query=source_projection_query(source_name),
        size=batch_size,
        preserve_order=False,
        scroll="2m",
    ):
        batch.append(hit)
        if len(batch) >= batch_size:
            yield batch
            batch = []
    if batch:
        yield batch


def backfill_source(
    es: Elasticsearch,
    source_name: str,
    aux_indexes: List[str],
    chunk_index: str = DEFAULT_CHUNK_INDEX,
    execute: bool = True,
    batch_size: int = 100,
) -> BackfillStats:
    """
    业务功能：针对单个 source 修复 kb_doc_meta/kb_doc_search 等辅助索引权限投影。
    关键流程：遍历指定辅助索引 -> 找到该 source 的文档级记录 -> 从 chunk 事实源反查投影 -> 原地 update。
    设计原因：在线写入失败补偿必须轻量、幂等、可重复执行，不能重新解析文档或重建向量。
    """

    stats = BackfillStats()
    indexes = [item.strip() for item in (aux_indexes or []) if item and item.strip()]
    for aux_index in indexes:
        for batch in scan_aux_docs_by_source(es, aux_index, source_name, batch_size):
            process_batch(
                es=es,
                aux_hits=batch,
                aux_index=aux_index,
                chunk_index=chunk_index,
                execute=execute,
                sample_left=0,
                stats=stats,
            )
    return stats


def projection_from_chunk_hit(hit: dict) -> ChunkProjection:
    """
    业务功能：从 chunk hit 提取辅助索引需要的最小权限投影。
    关键流程：source_index 取真实物理索引；owner/visible 兼容顶层和 metadata 两种历史位置。
    设计原因：辅助索引只需要过滤字段，不应复制正文、向量等重资产字段。
    """

    source = hit.get("_source") or {}
    metadata = source.get("metadata") or {}
    owner_unit_code = (
        source.get("owner_unit_code")
        or source.get("owner_dept_id")
        or metadata.get("owner_unit_code")
        or metadata.get("owner_dept_id")
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


def resolve_by_source(es: Elasticsearch, chunk_index: str, source_name: str) -> Optional[ChunkProjection]:
    """
    业务功能：根据辅助索引 source/source_name 反查一个最新 chunk 的权限投影。
    关键流程：同时尝试 metadata.source/source 的 keyword 与普通字段，兼容不同 mapping。
    设计原因：source 是文档级辅助索引和 chunk 的共同业务键，适合做历史修复的事实连接点。
    """

    source_name = (source_name or "").strip()
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
                "filter": [latest_filter("metadata.is_latest")],
                "should": [
                    {"term": {"metadata.source.keyword": source_name}},
                    {"term": {"metadata.source": source_name}},
                    {"term": {"source.keyword": source_name}},
                    {"term": {"source": source_name}},
                ],
                "minimum_should_match": 1,
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
    业务功能：构造写回辅助索引的最小权限字段集。
    关键流程：只包含 source_index/index_code/owner_unit_code/visible_unit_codes/permission_version。
    设计原因：修复权限投影不应影响相似搜索向量、关键词索引、标题正文等业务内容。
    """

    return {
        "source_index": projection.source_index,
        "index_code": index_code(projection.source_index),
        "owner_unit_code": projection.owner_unit_code,
        "visible_unit_codes": projection.visible_unit_codes,
        "permission_version": int(time.time() * 1000),
    }


def process_batch(
    es: Elasticsearch,
    aux_hits: List[dict],
    aux_index: str,
    chunk_index: str,
    execute: bool,
    sample_left: int,
    stats: BackfillStats,
) -> int:
    """
    业务功能：处理一批辅助索引文档并可选写回 ES。
    关键流程：按 source 做本地缓存，写回目标使用 hit._index，确保别名扫描到的物理索引被原地更新。
    设计原因：同一文档可能在多个辅助索引出现，缓存可降低 chunk 反查压力。
    """

    source_cache: Dict[str, Optional[ChunkProjection]] = {}
    actions = []

    for hit in aux_hits:
        stats.scanned += 1
        aux_source = hit.get("_source") or {}
        source_name = str(aux_source.get("source") or aux_source.get("source_name") or "").strip()
        if not source_name:
            stats.skipped_without_source += 1
            continue
        if source_name not in source_cache:
            source_cache[source_name] = resolve_by_source(es, chunk_index, source_name)
        projection = source_cache[source_name]
        if not projection or not projection.source_index:
            stats.skipped_unresolved += 1
            continue
        stats.resolved_by_source += 1
        update_doc = build_update_doc(projection)

        if sample_left > 0:
            print(
                "[sample] aux_index={aux_index}, id={id}, source={source}, source_index={source_index}, "
                "index_code={index_code}, owner_unit_code={owner_unit_code}, visible_unit_codes={visible_unit_codes}".format(
                    aux_index=hit.get("_index") or aux_index,
                    id=hit.get("_id"),
                    source=source_name,
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
                    "_index": hit.get("_index") or aux_index,
                    "_id": hit.get("_id"),
                    "script": {
                        "lang": "painless",
                        "source": """
for (def entry : params.fields.entrySet()) {
  def key = entry.getKey();
  def value = entry.getValue();
  if (ctx._source[key] == null || ctx._source[key] == '' || (ctx._source[key] instanceof List && ctx._source[key].isEmpty())) {
    ctx._source[key] = value;
  }
}
""",
                        "params": {"fields": update_doc},
                    },
                    "upsert": update_doc,
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
    业务功能：解析辅助索引权限投影补齐参数。
    关键流程：默认 dry-run；支持逗号分隔多个辅助索引；正式写入必须显式 --execute。
    设计原因：批量权限修复必须先预演再执行，降低误操作风险。
    """

    parser = argparse.ArgumentParser(description="Backfill permission projection fields for auxiliary doc indexes.")
    parser.add_argument("--indexes", default=DEFAULT_AUX_INDEXES, help="逗号分隔的辅助索引或别名，默认 kb_doc_search,kb_doc_meta")
    parser.add_argument("--chunk-index", default=DEFAULT_CHUNK_INDEX, help="chunk 读索引或别名，默认 kb_document")
    parser.add_argument("--batch-size", type=int, default=200, help="每批处理数量")
    parser.add_argument("--limit", type=int, default=0, help="每个辅助索引最多扫描数量，0 表示不限制")
    parser.add_argument("--sample", type=int, default=10, help="预览样例数量")
    parser.add_argument("--execute", action="store_true", help="真实写回 ES；未设置时只 dry-run")
    return parser.parse_args()


def main() -> None:
    """
    业务功能：执行文档级辅助索引权限投影补齐。
    关键流程：逐个索引扫描、反查 chunk、预演或写回、最后按索引 refresh 并输出统计。
    设计原因：逐索引统计可以明确定位剩余风险来自哪个辅助检索入口。
    """

    args = parse_args()
    es = create_es_client()
    indexes = [item.strip() for item in args.indexes.split(",") if item.strip()]
    mode = "execute" if args.execute else "dry-run"
    print(
        f"[Aux Permission Backfill] mode={mode}, indexes={indexes}, chunk_index={args.chunk_index}, "
        f"batch_size={args.batch_size}, limit={args.limit or 'all'}"
    )

    for aux_index in indexes:
        stats = BackfillStats()
        sample_left = max(args.sample, 0)
        limit = args.limit if args.limit > 0 else None
        for hits in scan_aux_docs(es, aux_index, args.batch_size, limit):
            sample_left = process_batch(
                es=es,
                aux_hits=hits,
                aux_index=aux_index,
                chunk_index=args.chunk_index,
                execute=args.execute,
                sample_left=sample_left,
                stats=stats,
            )
        if args.execute and stats.written > 0:
            es.indices.refresh(index=aux_index)
        print(f"[Aux Permission Backfill] index={aux_index} done")
        print(f"  scanned={stats.scanned}")
        print(f"  resolved_by_source={stats.resolved_by_source}")
        print(f"  skipped_without_source={stats.skipped_without_source}")
        print(f"  skipped_unresolved={stats.skipped_unresolved}")
        print(f"  dry_run_updates={stats.dry_run_updates}")
        print(f"  written={stats.written}")
        print(f"  failed={stats.failed}")


if __name__ == "__main__":
    main()
