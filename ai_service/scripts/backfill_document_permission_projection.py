"""
历史文档 chunk 权限投影字段回填脚本。

业务功能：
  为 kb_document_* 物理索引中的历史 chunk 补齐权限过滤需要的轻量字段：
  source_index / index_code / owner_unit_code / visible_unit_codes / permission_version。

关键流程：
  1. 扫描缺少任一权限投影字段的 kb_document_* 文档；
  2. source_index 来自 hit._index，这是权限过滤的事实来源；
  3. owner_unit_code 优先使用 ES 现有字段，其次查询 PostgreSQL kb_doc_registry；
  4. 只在显式传入 --execute 时写回 ES，默认 dry-run，防止批量误写；
  5. 写回使用 Painless 脚本只补空字段，不覆盖已有非空权限字段。

执行示例：
  python ai_service/scripts/backfill_document_permission_projection.py --limit 100
  python ai_service/scripts/backfill_document_permission_projection.py --execute --batch-size 500
"""

import argparse
import json
import os
import sys
import time
from dataclasses import asdict, dataclass
from typing import Dict, Iterable, List, Optional, Tuple

from dotenv import load_dotenv
from elasticsearch import Elasticsearch, helpers

load_dotenv()

DEFAULT_ES_HOST = os.getenv("ES_HOST", "http://localhost:9200")
DEFAULT_ES_USER = os.getenv("ES_USER", os.getenv("ES_USERNAME", ""))
DEFAULT_ES_PASS = os.getenv("ES_PASS", os.getenv("ES_PASSWORD", ""))
DEFAULT_INDEX_PATTERN = os.getenv("KB_DOCUMENT_BACKFILL_INDEX", "kb_document_*")

DEFAULT_PG_DSN = os.getenv(
    "PG_DSN",
    "host=127.0.0.1 port=5432 dbname=knowledge_base user=postgres password=liyz connect_timeout=3",
)

PERMISSION_FIELDS = [
    "acl_tokens",
    "source_index",
    "index_code",
    "owner_unit_code",
    "visible_unit_codes",
    "permission_version",
]


@dataclass
class Projection:
    """
    业务功能：承载单条 chunk 需要补齐的权限投影值。

    关键流程：source_index/index_code 来自索引名，owner_unit_code 来自 ES 或数据库，
    visible_unit_codes 至少包含 owner_unit_code，避免权限过滤字段为空导致文档不可解释。
    """

    source_index: str
    index_code: str
    owner_unit_code: str
    visible_unit_codes: List[str]
    acl_tokens: List[str]
    owner_source: str


@dataclass
class BackfillStats:
    """
    业务功能：记录回填全过程统计，便于 dry-run 与正式执行后对账。

    关键流程：区分 ES 字段命中、PG registry 命中和兜底值，避免把数据质量问题隐藏在总数里。
    """

    scanned: int = 0
    dry_run_updates: int = 0
    written: int = 0
    failed: int = 0
    owner_from_es: int = 0
    owner_from_pg: int = 0
    owner_defaulted: int = 0
    skipped_non_document_index: int = 0


class RegistryLookup:
    """
    业务功能：从 PostgreSQL kb_doc_registry 查询文档所属单位。

    关键流程：优先按 doc_id 精确查找，其次按 source_name 查找；使用内存缓存降低批量回填时的数据库压力。
    """

    def __init__(self, dsn: str, disabled: bool = False):
        self.disabled = disabled
        self.dsn = dsn
        self._conn = None
        self._doc_cache: Dict[str, Optional[str]] = {}
        self._source_cache: Dict[str, Optional[str]] = {}

    def close(self) -> None:
        if self._conn is not None:
            self._conn.close()
            self._conn = None

    def find_owner(self, doc_id: str, source_name: str) -> Optional[str]:
        if self.disabled:
            return None
        doc_id = (doc_id or "").strip()
        source_name = (source_name or "").strip()
        if doc_id:
            if doc_id not in self._doc_cache:
                self._doc_cache[doc_id] = self._query_one(
                    "select dept_code from kb_doc_registry where doc_id = %s and dept_code is not null and dept_code <> '' limit 1",
                    (doc_id,),
                )
            if self._doc_cache[doc_id]:
                return self._doc_cache[doc_id]
        if source_name:
            if source_name not in self._source_cache:
                self._source_cache[source_name] = self._query_one(
                    "select dept_code from kb_doc_registry where source_name = %s and dept_code is not null and dept_code <> '' order by doc_version desc nulls last limit 1",
                    (source_name,),
                )
            return self._source_cache[source_name]
        return None

    def _connect(self):
        if self._conn is None:
            try:
                import psycopg2
            except Exception as exc:
                print(f"[warn] psycopg2 unavailable, PG registry lookup disabled: {exc}", file=sys.stderr)
                self.disabled = True
                return None
            self._conn = psycopg2.connect(self.dsn)
        return self._conn

    def _query_one(self, sql: str, params: Tuple[str, ...]) -> Optional[str]:
        conn = self._connect()
        if conn is None:
            return None
        try:
            with conn.cursor() as cur:
                cur.execute(sql, params)
                row = cur.fetchone()
                return str(row[0]).strip() if row and row[0] else None
        except Exception as exc:
            print(f"[warn] PG registry lookup failed: {exc}", file=sys.stderr)
            return None


def create_es_client(es_host: str = DEFAULT_ES_HOST, es_user: str = DEFAULT_ES_USER, es_pass: str = DEFAULT_ES_PASS) -> Elasticsearch:
    """
    业务功能：创建 ES 客户端。

    关键流程：沿用项目环境变量命名，支持本地、容器和带 Basic Auth 的离线环境。
    """

    kwargs = {"hosts": [es_host]}
    if es_user:
        kwargs["basic_auth"] = (es_user, es_pass)
    return Elasticsearch(**kwargs)


def index_code(source_index: str) -> str:
    """
    业务功能：从 kb_document_* 物理索引名提取业务索引编码。

    关键流程：与在线写入链路保持一致，保证历史数据和新入库数据的 index_code 语义一致。
    """

    source_index = (source_index or "").strip()
    prefix = "kb_document_"
    if source_index.startswith(prefix):
        return source_index[len(prefix) :]
    return source_index


def normalize_codes(value) -> List[str]:
    """
    业务功能：把字符串、数组、集合等历史形态统一成单位编码列表。

    关键流程：只做类型归一化和去空值，不在此处推导组织树，避免离线脚本擅自扩大权限范围。
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


def infer_acl_tokens(source: dict, metadata: dict) -> List[str]:
    """
    业务功能：为缺少根级 acl_tokens 的历史 chunk 推导最小可用 ACL Token。
    关键流程：优先复用 ES 已存在的根级/metadata acl_tokens；若完全缺失，再按 visibility 做保守兜底。
    设计原因：离线脚本不能凭空推断私有授权关系，无法证明的 PRIVATE/GRANT 数据必须 fail-closed。
    """

    tokens = normalize_codes(source.get("acl_tokens")) or normalize_codes(metadata.get("acl_tokens"))
    if tokens:
        return tokens
    visibility = str(source.get("visibility") or metadata.get("visibility") or "INTERNAL").strip().upper()
    if visibility == "PUBLIC":
        return ["_PUBLIC"]
    if visibility == "INTERNAL":
        return ["_INTERNAL"]
    return ["_NO_ACCESS"]


def is_document_physical_index(index_name: str) -> bool:
    """
    业务功能：判断索引是否为可回填的文档物理索引。

    关键流程：只允许 kb_document_ 前缀的物理索引，排除别名、通配符和写别名，防止误更新非 chunk 索引。
    """

    name = (index_name or "").strip()
    return name.startswith("kb_document_") and "*" not in name and not name.endswith("_write")


def missing_permission_query() -> dict:
    """
    业务功能：定位缺少任一权限投影字段的历史 chunk。

    关键流程：缺字段和空字符串都纳入扫描；visible_unit_codes 只检查 exists，因为空数组在 ES 中通常等价于无索引值。
    """

    should = []
    for field in PERMISSION_FIELDS:
        should.append({"bool": {"must_not": [{"exists": {"field": field}}]}})
    return {
        "query": {"bool": {"should": should, "minimum_should_match": 1}},
        "_source": [
            "source_index",
            "acl_tokens",
            "index_code",
            "owner_unit_code",
            "visible_unit_codes",
            "visibility",
            "owner_dept_id",
            "metadata.acl_tokens",
            "metadata.visibility",
            "metadata.owner_unit_code",
            "metadata.owner_dept_id",
            "metadata.visible_unit_codes",
            "metadata.visible_depts",
            "metadata.doc_id",
            "metadata.source",
        ],
    }


def scan_docs(es: Elasticsearch, index_pattern: str, batch_size: int, limit: Optional[int]) -> Iterable[List[dict]]:
    """
    业务功能：流式扫描待补齐的 chunk 文档。

    关键流程：使用 helpers.scan 避免深分页；limit 只用于灰度抽样和测试，不改变查询语义。
    """

    batch: List[dict] = []
    scanned = 0
    for hit in helpers.scan(
        es,
        index=index_pattern,
        query=missing_permission_query(),
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


def projection_from_hit(hit: dict, registry: RegistryLookup, default_owner: str) -> Optional[Projection]:
    """
    业务功能：从 ES hit 与数据库 registry 推导最小权限投影。

    关键流程：source_index 必须来自 hit._index；owner 优先级为 ES 顶层、metadata、PG registry、默认值。
    """

    source_index = hit.get("_index") or ""
    if not is_document_physical_index(source_index):
        return None
    source = hit.get("_source") or {}
    metadata = source.get("metadata") or {}
    owner = (
        source.get("owner_unit_code")
        or source.get("owner_dept_id")
        or metadata.get("owner_unit_code")
        or metadata.get("owner_dept_id")
        or ""
    )
    owner_source = "es" if str(owner).strip() else ""
    if not owner:
        owner = registry.find_owner(str(metadata.get("doc_id") or ""), str(metadata.get("source") or ""))
        owner_source = "pg" if owner else ""
    if not owner:
        owner = default_owner
        owner_source = "default"

    visible = (
        normalize_codes(source.get("visible_unit_codes"))
        or normalize_codes(metadata.get("visible_unit_codes"))
        or normalize_codes(metadata.get("visible_depts"))
        or normalize_codes(owner)
    )
    return Projection(
        source_index=source_index,
        index_code=index_code(source_index),
        owner_unit_code=str(owner).strip(),
        visible_unit_codes=visible,
        acl_tokens=infer_acl_tokens(source, metadata),
        owner_source=owner_source,
    )


def build_update_doc(projection: Projection) -> dict:
    """
    业务功能：构造写回 ES 的权限投影字段。

    关键流程：只包含权限过滤需要的字段，不触碰 content/vector/metadata 等业务正文。
    """

    return {
        "source_index": projection.source_index,
        "index_code": projection.index_code,
        "owner_unit_code": projection.owner_unit_code,
        "visible_unit_codes": projection.visible_unit_codes,
        "acl_tokens": projection.acl_tokens,
        "permission_version": int(time.time() * 1000),
    }


def process_batch(
    es: Elasticsearch,
    hits: List[dict],
    execute: bool,
    sample_left: int,
    registry: RegistryLookup,
    default_owner: str,
    stats: BackfillStats,
) -> int:
    """
    业务功能：处理一批 chunk 文档并可选写回 ES。

    关键流程：写回目标使用 hit._index/hit._id；正式执行时使用脚本只填空字段，避免覆盖已有正确权限值。
    """

    actions = []
    for hit in hits:
        stats.scanned += 1
        projection = projection_from_hit(hit, registry, default_owner)
        if projection is None:
            stats.skipped_non_document_index += 1
            continue
        if projection.owner_source == "es":
            stats.owner_from_es += 1
        elif projection.owner_source == "pg":
            stats.owner_from_pg += 1
        else:
            stats.owner_defaulted += 1

        update_doc = build_update_doc(projection)
        if sample_left > 0:
            print(
                "[sample] index={index}, id={id}, source_index={source_index}, "
                "index_code={index_code}, owner_unit_code={owner_unit_code}, owner_source={owner_source}, visible_unit_codes={visible_unit_codes}, acl_tokens={acl_tokens}".format(
                    index=hit.get("_index"),
                    id=hit.get("_id"),
                    source_index=update_doc["source_index"],
                    index_code=update_doc["index_code"],
                    owner_unit_code=update_doc["owner_unit_code"],
                    owner_source=projection.owner_source,
                    visible_unit_codes=",".join(update_doc["visible_unit_codes"]),
                    acl_tokens=",".join(update_doc["acl_tokens"]),
                )
            )
            sample_left -= 1

        if execute:
            actions.append(
                {
                    "_op_type": "update",
                    "_index": hit.get("_index"),
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
    业务功能：解析命令行参数。

    关键流程：默认 dry-run，正式写入必须显式 --execute，确保批量任务先审计后执行。
    """

    parser = argparse.ArgumentParser(description="Backfill permission projection fields for kb_document_* chunks.")
    parser.add_argument("--index", "--indices", dest="index", default=DEFAULT_INDEX_PATTERN,
                        help="待扫描索引或通配符，默认 kb_document_*")
    parser.add_argument("--es-host", default=DEFAULT_ES_HOST, help="ES 地址")
    parser.add_argument("--es-user", default=DEFAULT_ES_USER, help="ES Basic Auth 用户名")
    parser.add_argument("--es-pass", default=DEFAULT_ES_PASS, help="ES Basic Auth 密码")
    parser.add_argument("--batch-size", type=int, default=500, help="每批处理数量")
    parser.add_argument("--limit", type=int, default=0, help="最多扫描数量，0 表示不限")
    parser.add_argument("--sample", type=int, default=10, help="预览样例数量")
    parser.add_argument("--execute", action="store_true", help="真实写回 ES；未设置时只 dry-run")
    parser.add_argument("--output", default="", help="输出结构化 JSON 报告路径")
    parser.add_argument("--pg-dsn", default=DEFAULT_PG_DSN, help="PostgreSQL DSN，用于查 kb_doc_registry")
    parser.add_argument("--no-pg", action="store_true", help="禁用数据库单位补齐，仅使用 ES 字段与默认值")
    parser.add_argument("--default-owner", default="global", help="ES/PG 均无单位字段时的兜底 owner_unit_code")
    return parser.parse_args()


def build_report(args: argparse.Namespace, stats: BackfillStats, started_at: int, finished_at: int) -> dict:
    """
    业务功能：构造可归档的结构化执行报告。
    关键流程：记录模式、索引、批量参数、PG 是否启用和统计值，便于离线审批与回放。
    设计原因：生产 dry-run 不能只依赖控制台日志，必须产出机器可读报告。
    """

    return {
        "mode": "execute" if args.execute else "dry-run",
        "index": args.index,
        "es_host": args.es_host,
        "batch_size": args.batch_size,
        "limit": args.limit,
        "sample": args.sample,
        "pg_enabled": not args.no_pg,
        "default_owner": args.default_owner,
        "started_at": started_at,
        "finished_at": finished_at,
        "stats": asdict(stats),
    }


def main() -> None:
    """
    业务功能：执行历史 chunk 权限字段回填。

    关键流程：打印参数、流式扫描、分批处理、最后 refresh 与输出对账统计。
    """

    args = parse_args()
    started_at = int(time.time() * 1000)
    es = create_es_client(args.es_host, args.es_user, args.es_pass)
    registry = RegistryLookup(args.pg_dsn, disabled=args.no_pg)
    stats = BackfillStats()
    sample_left = max(args.sample, 0)
    limit = args.limit if args.limit > 0 else None
    mode = "execute" if args.execute else "dry-run"
    print(
        f"[Document Permission Backfill] mode={mode}, index={args.index}, batch_size={args.batch_size}, "
        f"limit={limit or 'all'}, pg={'disabled' if args.no_pg else 'enabled'}, default_owner={args.default_owner}"
    )
    try:
        for hits in scan_docs(es, args.index, args.batch_size, limit):
            sample_left = process_batch(
                es=es,
                hits=hits,
                execute=args.execute,
                sample_left=sample_left,
                registry=registry,
                default_owner=args.default_owner,
                stats=stats,
            )
        if args.execute and stats.written > 0:
            es.indices.refresh(index=args.index)
    finally:
        registry.close()

    print("[Document Permission Backfill] done")
    print(f"  scanned={stats.scanned}")
    print(f"  owner_from_es={stats.owner_from_es}")
    print(f"  owner_from_pg={stats.owner_from_pg}")
    print(f"  owner_defaulted={stats.owner_defaulted}")
    print(f"  skipped_non_document_index={stats.skipped_non_document_index}")
    print(f"  dry_run_updates={stats.dry_run_updates}")
    print(f"  written={stats.written}")
    print(f"  failed={stats.failed}")
    report = build_report(args, stats, started_at, int(time.time() * 1000))
    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            json.dump(report, fh, ensure_ascii=False, indent=2)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
