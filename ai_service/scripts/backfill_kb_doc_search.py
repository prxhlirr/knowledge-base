"""
Backfill compact document-level keyword records into kb_doc_search_v1.

It streams existing kb_document_* chunks with PIT/search_after, groups chunks by
stable document identity, and writes one searchable record per source document.
The job is idempotent because the target _id is derived from md5(source)_v{max_version},
matching the online pipeline's formula in doc_indexer.py:update_doc_search().

Modes:
  full      (default) — full backfill, scan all chunks and rewrite all documents
  reconcile — backfill missing documents AND repair existing documents with empty fields
  validate  — post-migration validation (count parity, field completeness, _id consistency, duplicates)

Usage:
  python backfill_kb_doc_search.py                          # full mode
  BACKFILL_MODE=reconcile python backfill_kb_doc_search.py  # reconcile + repair mode
  BACKFILL_MODE=validate python backfill_kb_doc_search.py   # validation only
  DRY_RUN=true python backfill_kb_doc_search.py             # dry run (full/reconcile)
"""

import base64
import hashlib
import json
import os
import re as _re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from core.normalization.text_normalizer import (  # noqa: E402
    normalize_content_if_fully_encoded,
    normalize_filename,
    normalize_list,
    normalize_metadata_text,
)


ES_HOST = os.getenv("ES_HOST", "http://localhost:9200").rstrip("/")
ES_USER = os.getenv("ES_USER", os.getenv("ES_USERNAME", ""))
ES_PASS = os.getenv("ES_PASS", os.getenv("ES_PASSWORD", ""))
SOURCE_INDEX = os.getenv("SOURCE_INDEX", "kb_document_*")
DOC_SEARCH_INDEX = os.getenv("KB_DOC_SEARCH_INDEX", "kb_doc_search_v1")
DOC_SEARCH_READ_ALIAS = os.getenv("KB_DOC_SEARCH_READ_ALIAS", "kb_doc_search")
DOC_SEARCH_WRITE_ALIAS = os.getenv("KB_DOC_SEARCH_WRITE_ALIAS", "kb_doc_search_write")
BATCH_SIZE = int(os.getenv("DOC_SEARCH_BACKFILL_BATCH_SIZE", "2000"))
DRY_RUN = os.getenv("DRY_RUN", "false").lower() == "true"
BACKFILL_MODE = os.getenv("BACKFILL_MODE", "full").lower()  # full | reconcile | validate
DEFAULT_ACL_TOKENS = [
    token.strip()
    for token in os.getenv("KB_DOC_META_DEFAULT_ACL_TOKENS", "_INTERNAL").split(",")
    if token.strip()
] or ["_INTERNAL"]

BULK_DELAY = float(os.getenv("BACKFILL_BULK_DELAY", "0.1"))
RECONCILE_BATCH = int(os.getenv("RECONCILE_BATCH_SIZE", "200"))
CHECKPOINT_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "backfill_checkpoint.json")

# _source fields for chunk reads (shared by full and reconcile modes)
_CHUNK_SOURCE_FIELDS = [
    "content", "display_content", "content_hash", "keywords", "acl_tokens",
    "chunk_granularity", "metadata.source", "metadata.title", "metadata.document_number",
    "metadata.doc_id", "metadata.content_hash", "metadata.owner",
    "metadata.tags", "metadata.tags_kw", "metadata.section_path", "metadata.doc_type",
    "metadata.data_source", "metadata.acl_tokens", "metadata.visibility",
    "metadata.owner_dept_id", "metadata.publish_time", "metadata.doc_version",
    "metadata.is_latest", "metadata.quality_score", "metadata.chunk_id",
]

# Filter clause shared by full and reconcile modes
_LATEST_FILTER = [
    {"bool": {"should": [
        {"term": {"metadata.is_latest": True}},
        {"bool": {"must_not": {"exists": {"field": "metadata.is_latest"}}}},
    ], "minimum_should_match": 1}}
]


def es_request(method, path, body=None, timeout=120):
    url = ES_HOST + path
    data = body.encode("utf-8") if isinstance(body, str) else (
        json.dumps(body).encode("utf-8") if body is not None else None
    )
    headers = {"Content-Type": "application/json"}
    if ES_USER:
        token = base64.b64encode(f"{ES_USER}:{ES_PASS}".encode("utf-8")).decode("ascii")
        headers["Authorization"] = f"Basic {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            payload = json.loads(raw) if raw else {}
        except Exception:
            payload = {"error": raw}
        raise RuntimeError(f"ES {method} {path} failed: {e.code} {payload}")


# ── Bulk Writer (批量写入, index 动作替代 update+upsert) ──────────────────────

# Painless 脚本：只填充空字段，不覆盖非空值（用于 repair 场景）
_REPAIR_SCRIPT = """
for (def entry : params.entrySet()) {
    if (ctx._source[entry.getKey()] == null || ctx._source[entry.getKey()] == '') {
        ctx._source[entry.getKey()] = entry.getValue();
    }
}
"""


class BulkWriter:
    """Accumulates document bodies and flushes via ES Bulk API.

    Actions:
      - index: direct write (for missing documents), 2-3x faster than update+upsert
      - update+script: repair mode, only fills empty fields without overwriting non-empty ones
    """

    def __init__(self, max_batch=1000, flush_interval=5.0):
        self.actions = []  # list of (doc_id, body_dict, is_repair)
        self.max_batch = max_batch
        self.flush_interval = flush_interval
        self._last_flush = time.time()
        self.written = 0

    def add(self, doc_id, body, is_repair=False):
        self.actions.append((doc_id, body, is_repair))
        if len(self.actions) >= self.max_batch or (time.time() - self._last_flush) >= self.flush_interval:
            self.flush()

    def flush(self):
        if not self.actions:
            return
        if DRY_RUN:
            self.written += len(self.actions)
            self.actions.clear()
            self._last_flush = time.time()
            return

        check_es_health()

        ndjson_lines = []
        for doc_id, body, is_repair in self.actions:
            if is_repair:
                # repair: update with Painless script, only fill empty fields
                action = {"update": {"_index": DOC_SEARCH_WRITE_ALIAS, "_id": doc_id}}
                body_line = {
                    "script": {"source": _REPAIR_SCRIPT, "lang": "painless", "params": body},
                    "upsert": body,
                }
            else:
                # index: direct write (2-3x faster than update+upsert)
                action = {"index": {"_index": DOC_SEARCH_WRITE_ALIAS, "_id": doc_id}}
                body_line = body
            ndjson_lines.append(json.dumps(action, ensure_ascii=False))
            ndjson_lines.append(json.dumps(body_line, ensure_ascii=False))
        ndjson_body = "\n".join(ndjson_lines) + "\n"
        resp = es_request("POST", f"/{DOC_SEARCH_WRITE_ALIAS}/_bulk", ndjson_body, timeout=120)
        items = resp.get("items", [])
        errors = [i for i in items if (i.get("update") or i.get("index") or {}).get("status", 500) >= 400]
        if errors:
            print(f"[DocSearch] bulk partial failure: {len(errors)}/{len(items)}", file=sys.stderr)
        self.written += len(self.actions) - len(errors)
        self.actions.clear()
        self._last_flush = time.time()
        rate_limit()


# ── Checkpoint (亿级优化: 断点恢复) ──────────────────────────────────────────

def load_checkpoint():
    try:
        with open(CHECKPOINT_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return None


def save_checkpoint(data):
    data["timestamp"] = time.time()
    with open(CHECKPOINT_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)


def clear_checkpoint():
    try:
        os.remove(CHECKPOINT_FILE)
    except FileNotFoundError:
        pass


# ── Rate Limit (亿级优化: 速率控制) ─────────────────────────────────────────

def rate_limit():
    if BULK_DELAY > 0:
        time.sleep(BULK_DELAY)


# ── ES Health Check (保护单节点 ES 不被压垮) ─────────────────────────────────

def check_es_health():
    """Check ES heap usage before each bulk flush. Throttle if heap > 85%."""
    try:
        nodes = es_request("GET", "/_nodes/stats/jvm?filter_path=nodes.*.jvm.mem.heap_used_percent")
        for node in nodes.get("nodes", {}).values():
            heap = node.get("jvm", {}).get("mem", {}).get("heap_used_percent", 0)
            if heap > 85:
                print(f"[Throttle] ES heap at {heap}%, sleeping 5s...")
                time.sleep(5)
    except Exception:
        pass  # Health check failure should not block migration


# ── Progress Tracker (迁移进度 + ETA 估算) ──────────────────────────────────

_progress_start = None
_progress_last_print = 0

def print_progress(written, total_docs, seen_chunks=0, interval=30):
    """Print migration progress with throughput and ETA estimation."""
    global _progress_start, _progress_last_print
    now = time.time()
    if _progress_start is None:
        _progress_start = now
        _progress_last_print = now
        return
    if now - _progress_last_print < interval:
        return
    elapsed = now - _progress_start
    throughput = written / elapsed if elapsed > 0 else 0
    remaining = total_docs - written
    eta = remaining / throughput if throughput > 0 else 0
    pct = written / total_docs * 100 if total_docs > 0 else 0
    print(f"[Progress] {pct:.1f}% | {written}/{total_docs} docs | "
          f"chunks={seen_chunks} | {throughput:.0f} docs/s | "
          f"ETA: {int(eta // 60)}m{int(eta % 60)}s")
    _progress_last_print = now


# ── Scroll Helper (亿级优化: 大文档完整读取) ──────────────────────────────────

def scroll_all_hits(index, query_body, scroll_keep_alive="2m"):
    """Yield all hits for a query using scroll pagination."""
    body = dict(query_body)
    body["size"] = min(body.get("size", 5000), 5000)
    resp = es_request("POST", f"/{index}/_search?scroll={scroll_keep_alive}", body, timeout=180)
    scroll_id = resp.get("_scroll_id")
    total = resp.get("hits", {}).get("total", {}).get("value", 0)
    hits = resp.get("hits", {}).get("hits", [])
    yield from hits
    while len(hits) < total and hits:
        resp = es_request("POST", "/_search/scroll",
                          {"scroll_id": scroll_id, "scroll": scroll_keep_alive}, timeout=60)
        hits = resp.get("hits", {}).get("hits", [])
        if not hits:
            break
        yield from hits
        scroll_id = resp.get("_scroll_id", scroll_id)
    if scroll_id:
        try:
            es_request("DELETE", "/_search/scroll", {"scroll_id": scroll_id})
        except Exception:
            pass


def doc_search_mapping():
    return {
        "settings": {
            "number_of_shards": int(os.getenv("KB_DOC_SEARCH_SHARDS", "1")),
            "number_of_replicas": int(os.getenv("KB_DOC_SEARCH_REPLICAS", "0")),
            # 解决 ES 7.0+ 默认最大 ngram 差值为 1 的限制（我们这里是 8 - 2 = 6），必须显式设定允许的最大差值
            "max_ngram_diff": 6,
            "analysis": {
                "tokenizer": {
                    "doc_ngram_tokenizer": {
                        "type": "ngram",
                        "min_gram": 2,
                        "max_gram": 8,
                        # 不指定 token_chars，让 ngram tokenizer 处理所有字符类型（包括 CJK 汉字）。
                        # 原配置 ["letter", "digit"] 排除了中文字符（Unicode Lo 类别），
                        # 导致中文文件名/文号的 .ngram 子字段产生零 token，ngram 子串匹配完全失效。
                        # 此修复与 es_setup.py 中的 doc_search_index_mapping() 保持一致。
                    }
                },
                "analyzer": {
                    "ik_smart": {"type": "custom", "tokenizer": "ik_smart"},
                    "ik_max_word": {"type": "custom", "tokenizer": "ik_max_word"},
                    "doc_ngram": {
                        "type": "custom",
                        "tokenizer": "doc_ngram_tokenizer",
                        "filter": ["lowercase"],
                    },
                },
            },
        },
        "mappings": {
            "dynamic": "strict",
            "properties": {
                "doc_id": {"type": "keyword"},
                "doc_version": {"type": "integer"},
                "content_hash": {"type": "keyword"},
                "source": {
                    "type": "keyword",
                    "fields": {
                        "text": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                        "ngram": {"type": "text", "analyzer": "doc_ngram", "search_analyzer": "doc_ngram"},
                    },
                },
                "source_name": {"type": "keyword"},
                "title": {
                    "type": "text",
                    "analyzer": "ik_max_word",
                    "search_analyzer": "ik_smart",
                    "fields": {
                        "keyword": {"type": "keyword", "ignore_above": 256},
                        "ngram": {"type": "text", "analyzer": "doc_ngram", "search_analyzer": "doc_ngram"},
                    },
                },
                "document_number": {
                    "type": "keyword",
                    "fields": {
                        "text": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                        "ngram": {"type": "text", "analyzer": "doc_ngram", "search_analyzer": "doc_ngram"},
                    },
                },
                "keywords": {"type": "keyword"},
                "tags": {"type": "keyword"},
                "entities": {"type": "keyword"},
                "section_titles": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                "doc_terms": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                "summary": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                "representative_chunk_ids": {"type": "keyword"},
                "doc_type": {"type": "keyword"},
                "data_source": {"type": "keyword"},
                "is_latest": {"type": "boolean"},
                "acl_tokens": {"type": "keyword"},
                "visibility": {"type": "keyword"},
                "owner_dept_id": {"type": "keyword"},
                "publish_time": {"type": "date", "format": "yyyy-MM-dd||epoch_millis"},
                "chunk_count": {"type": "integer"},
                "updated_at": {"type": "date", "format": "epoch_millis"},
            },
        },
    }


def ensure_doc_search_index():
    try:
        es_request("HEAD", f"/{DOC_SEARCH_INDEX}")
        exists = True
    except Exception:
        exists = False
    if not exists:
        es_request("PUT", f"/{DOC_SEARCH_INDEX}", doc_search_mapping())
        print(f"[DocSearch] created index {DOC_SEARCH_INDEX}")

    actions = []
    try:
        alias_resp = es_request("GET", f"/{DOC_SEARCH_INDEX}/_alias")
        aliases = alias_resp.get(DOC_SEARCH_INDEX, {}).get("aliases", {})
    except Exception:
        aliases = {}

    for alias in (DOC_SEARCH_READ_ALIAS, DOC_SEARCH_WRITE_ALIAS):
        try:
            alias_refs = es_request("GET", f"/_alias/{alias}")
        except Exception:
            alias_refs = {}
        for index_name in alias_refs.keys():
            if index_name != DOC_SEARCH_INDEX:
                actions.append({"remove": {"index": index_name, "alias": alias}})
    if DOC_SEARCH_READ_ALIAS not in aliases:
        actions.append({"add": {"index": DOC_SEARCH_INDEX, "alias": DOC_SEARCH_READ_ALIAS}})
    if aliases.get(DOC_SEARCH_WRITE_ALIAS, {}).get("is_write_index") is not True:
        if DOC_SEARCH_WRITE_ALIAS in aliases:
            actions.append({"remove": {"index": DOC_SEARCH_INDEX, "alias": DOC_SEARCH_WRITE_ALIAS}})
        actions.append({"add": {"index": DOC_SEARCH_INDEX, "alias": DOC_SEARCH_WRITE_ALIAS, "is_write_index": True}})
    if actions:
        es_request("POST", "/_aliases", {"actions": actions})
        print(f"[DocSearch] aliases registered: {DOC_SEARCH_READ_ALIAS}, {DOC_SEARCH_WRITE_ALIAS}")


def open_pit():
    resp = es_request("POST", f"/{SOURCE_INDEX}/_pit?keep_alive=5m")
    pit_id = resp.get("id")
    if not pit_id:
        raise RuntimeError(f"open PIT failed: {resp}")
    return pit_id


def close_pit(pit_id):
    if pit_id:
        try:
            es_request("DELETE", "/_pit", {"id": pit_id})
        except Exception as exc:
            print(f"[DocSearch] close PIT skipped: {exc}", file=sys.stderr)


def add_unique(target, value, limit):
    for text in normalize_list(value, limit):
        if text and text not in target:
            target.append(text)
            if len(target) >= limit:
                return


def group_key_for(src, hit_id):
    meta = src.get("metadata") or {}
    return meta.get("doc_id") or src.get("doc_id") \
        or meta.get("content_hash") or src.get("content_hash") \
        or meta.get("source") or src.get("source") or hit_id


def stable_id(doc):
    """Generate _id matching the online pipeline's formula: md5(source)_v{max_version}.

    The online pipeline (rag_pipeline.py:1153, doc_indexer.py:217) computes:
      file_base_hash = hashlib.md5(source_name.encode('utf-8')).hexdigest()
      doc_id = f"{file_base_hash}_v{new_version}"
      _id = urllib.parse.quote(doc_id, safe="")
    This function reproduces the same formula from chunk data.
    """
    source = doc.get("source", "")
    max_version = doc.get("doc_version") or 1
    file_base_hash = hashlib.md5(source.encode("utf-8")).hexdigest()
    raw_id = f"{file_base_hash}_v{max_version}"
    return urllib.parse.quote(raw_id, safe="")


def physical_index(index_name):
    index_name = (index_name or "").strip()
    if index_name.endswith("_write"):
        return index_name[:-len("_write")]
    return index_name


def index_code(index_name):
    index_name = physical_index(index_name)
    prefix = "kb_document_"
    return index_name[len(prefix):] if index_name.startswith(prefix) else index_name


def new_group(src, hit_id):
    meta = src.get("metadata") or {}
    source = normalize_filename(meta.get("source") or src.get("source") or hit_id)
    title = normalize_metadata_text(meta.get("title") or src.get("title") or source)
    document_number = normalize_metadata_text(meta.get("document_number") or src.get("document_number") or "")
    content = normalize_content_if_fully_encoded(src.get("display_content") or src.get("content") or "").strip()
    return {
        "group_key": group_key_for(src, hit_id),
        "doc_id": meta.get("doc_id") or src.get("doc_id") or "",
        "doc_version": meta.get("doc_version") or src.get("doc_version"),
        "content_hash": meta.get("content_hash") or src.get("content_hash") or "",
        "source": source,
        "title": title,
        "document_number": document_number,
        "keywords": [],
        "tags": [],
        "entities": [],
        "section_titles": [],
        "summary_parts": [content[:500]] if content else [],
        "term_parts": [source, title, document_number],
        "representative_chunk_ids": [],
        "doc_type": meta.get("doc_type") or "",
        "data_source": meta.get("data_source") or "document",
        "acl_tokens": set(meta.get("acl_tokens") or src.get("acl_tokens") or DEFAULT_ACL_TOKENS),
        "visibility": meta.get("visibility") or "",
        "owner_dept_id": meta.get("owner_dept_id") or "",
        "source_index": physical_index(src.get("_index") or ""),
        "publish_time": meta.get("publish_time"),
        "chunk_count": 0,
    }


def add_chunk(group, src):
    meta = src.get("metadata") or {}
    group["chunk_count"] += 1
    # Track max version for stable_id (must match online pipeline's _id formula)
    chunk_version = meta.get("doc_version")
    if chunk_version and (group["doc_version"] is None or chunk_version > group["doc_version"]):
        group["doc_version"] = chunk_version
    add_unique(group["keywords"], src.get("keywords"), 300)
    add_unique(group["keywords"], meta.get("tags_kw"), 300)
    add_unique(group["tags"], meta.get("tags"), 100)
    add_unique(group["tags"], meta.get("tags_kw"), 100)
    add_unique(group["section_titles"], meta.get("section_path"), 120)
    add_unique(group["entities"], meta.get("owner"), 100)
    add_unique(group["entities"], meta.get("document_number"), 100)
    for token in meta.get("acl_tokens") or src.get("acl_tokens") or []:
        if token:
            group["acl_tokens"].add(str(token))
    chunk_id = meta.get("chunk_id") or src.get("chunk_id")
    if chunk_id is not None and len(group["representative_chunk_ids"]) < 32:
        group["representative_chunk_ids"].append(str(chunk_id))
    content = normalize_content_if_fully_encoded(src.get("display_content") or src.get("content") or "").strip()
    if content:
        if len(group["summary_parts"]) < 3 and content[:500] not in group["summary_parts"]:
            group["summary_parts"].append(content[:500])
        if len(group["term_parts"]) < 24:
            group["term_parts"].append(content[:600])


def build_doc_body(group):
    """Build the ES document body from a group (pure function, no I/O). Returns (doc_id, body) or (None, None)."""
    if not group or group["chunk_count"] <= 0:
        return None, None
    doc_terms = normalize_metadata_text(" ".join(str(part) for part in group["term_parts"] if part))
    for extra in (group["keywords"][:120], group["tags"][:80], group["section_titles"][:80]):
        if extra:
            doc_terms = f"{doc_terms} {' '.join(extra)}".strip()
    body = {
        "doc_id": group["doc_id"],
        "doc_version": group["doc_version"],
        "content_hash": group["content_hash"],
        "source": group["source"],
        "source_name": group["source"],
        "title": group["title"],
        "document_number": group["document_number"],
        "keywords": group["keywords"],
        "tags": group["tags"],
        "entities": group["entities"],
        "section_titles": group["section_titles"],
        "doc_terms": doc_terms[:12000],
        "summary": normalize_content_if_fully_encoded(" ".join(group["summary_parts"]))[:1500],
        "representative_chunk_ids": group["representative_chunk_ids"],
        "doc_type": group["doc_type"],
        "data_source": group["data_source"],
        "is_latest": True,
        "acl_tokens": sorted(group["acl_tokens"]),
        "visibility": group["visibility"],
        "owner_dept_id": group["owner_dept_id"],
        "source_index": group.get("source_index") or "",
        "index_code": index_code(group.get("source_index") or ""),
        "owner_unit_code": group.get("owner_dept_id") or "",
        "visible_unit_codes": [group.get("owner_dept_id")] if group.get("owner_dept_id") else [],
        "permission_version": int(time.time() * 1000),
        "publish_time": group["publish_time"],
        "chunk_count": group["chunk_count"],
        "updated_at": int(time.time() * 1000),
    }
    if not body["publish_time"]:
        body.pop("publish_time", None)
    doc_id = stable_id(body)
    return doc_id, body


def write_group(group):
    """Backward-compatible single-doc write (used by legacy callers)."""
    doc_id, body = build_doc_body(group)
    if doc_id is None:
        return False
    if DRY_RUN:
        return True
    # Use direct index action (same as online pipeline), not update+upsert
    es_request("PUT", f"/{DOC_SEARCH_WRITE_ALIAS}/_doc/{doc_id}", body)
    return True


def get_source_list():
    """Get all unique source names from kb_document_* via composite agg (pagination-safe at billion scale)."""
    sources = set()
    after_key = None
    while True:
        composite = {
            "size": 5000,
            "sources": [{"source": {"terms": {"field": "metadata.source"}}}],
        }
        if after_key is not None:
            composite["after"] = after_key
        body = {
            "size": 0,
            "query": {
                "bool": {
                    "must_not": [{"term": {"chunk_granularity": "coarse"}}],
                    "filter": _LATEST_FILTER,
                }
            },
            "aggs": {"unique_sources": {"composite": composite}},
        }
        resp = es_request("POST", f"/{SOURCE_INDEX}/_search", body, timeout=180)
        agg = resp.get("aggregations", {}).get("unique_sources", {})
        buckets = agg.get("buckets", [])
        for b in buckets:
            sources.add(b["key"]["source"])
        after_key = agg.get("after_key")
        if not buckets or after_key is None:
            break
    return sources


def get_existing_sources():
    """Get all source names currently in kb_doc_search via scroll."""
    sources = set()
    body = {
        "size": 1000,
        "_source": ["source"],
        "query": {"term": {"is_latest": True}},
    }
    scroll_id = None
    try:
        while True:
            if scroll_id:
                resp = es_request("POST", "/_search/scroll", {"scroll_id": scroll_id, "scroll": "2m"}, timeout=60)
            else:
                resp = es_request("POST", f"/{DOC_SEARCH_READ_ALIAS}/_search?scroll=2m", body, timeout=60)
            hits = resp.get("hits", {}).get("hits", [])
            if not hits:
                break
            for h in hits:
                s = h.get("_source", {})
                src = s.get("source")
                if src:
                    sources.add(src)
            scroll_id = resp.get("_scroll_id")
        return sources
    finally:
        if scroll_id:
            try:
                es_request("DELETE", "/_search/scroll", {"scroll_id": scroll_id})
            except Exception:
                pass


def get_stale_sources():
    """Find documents in kb_doc_search with empty required fields (repair candidates)."""
    stale = set()
    body = {
        "size": 1000,
        "_source": ["source"],
        "query": {
            "bool": {
                "should": [
                    {"bool": {"must_not": {"exists": {"field": "doc_id"}}}},
                    {"term": {"doc_id": ""}},
                    {"bool": {"must_not": {"exists": {"field": "content_hash"}}}},
                    {"term": {"content_hash": ""}},
                ],
                "minimum_should_match": 1,
            }
        }
    }
    for hit in scroll_all_hits(DOC_SEARCH_READ_ALIAS, body):
        src = hit.get("_source", {})
        if src.get("source"):
            stale.add(src["source"])
    return stale


def get_source_cardinality():
    """Get unique source count from kb_document* via cardinality aggregation."""
    body = {
        "size": 0,
        "query": {
            "bool": {
                "must_not": [{"term": {"chunk_granularity": "coarse"}}],
                "filter": _LATEST_FILTER,
            }
        },
        "aggs": {"n": {"cardinality": {"field": "metadata.source"}}},
    }
    resp = es_request("POST", f"/{SOURCE_INDEX}/_search", body, timeout=180)
    return resp.get("aggregations", {}).get("n", {}).get("value", 0)


def backfill_by_sources(missing_sources, stale_sources=None):
    """Backfill missing source documents and repair stale ones using batch queries + scroll + BulkWriter."""
    stale_sources = stale_sources or set()
    if not missing_sources and not stale_sources:
        print("[DocSearch] reconcile: no missing or stale documents, nothing to do")
        return 0

    # Resume from checkpoint if available
    all_to_process = sorted(missing_sources | stale_sources)
    processed = set()
    cp = load_checkpoint()
    if cp and cp.get("mode") == "reconcile":
        processed = set(cp.get("processed_sources", []))
        all_to_process = [s for s in all_to_process if s not in processed]
        if processed:
            print(f"[DocSearch] checkpoint: skipping {len(processed)} already-processed sources")

    if not all_to_process:
        print("[DocSearch] reconcile: all sources already processed (from checkpoint)")
        return 0

    total_to_process = len(all_to_process)
    missing_set = set(missing_sources)
    print(f"[DocSearch] reconcile: {len(missing_sources)} missing + {len(stale_sources)} stale = "
          f"{total_to_process} documents to process")

    bulk = BulkWriter(max_batch=1000)
    written = 0
    seen = 0

    for batch_start in range(0, len(all_to_process), RECONCILE_BATCH):
        batch = all_to_process[batch_start:batch_start + RECONCILE_BATCH]
        query_body = {
            "size": 5000,
            "_source": _CHUNK_SOURCE_FIELDS,
            "query": {
                "bool": {
                    "must": [{"terms": {"metadata.source": batch}}],
                    "must_not": [{"term": {"chunk_granularity": "coarse"}}],
                    "filter": _LATEST_FILTER,
                }
            },
            "sort": [
                {"metadata.source": {"order": "asc", "unmapped_type": "keyword"}},
                {"metadata.chunk_id": {"order": "asc", "unmapped_type": "integer"}},
            ],
        }

        # Use scroll to handle documents with many chunks
        groups = {}  # source_name -> group dict
        for hit in scroll_all_hits(SOURCE_INDEX, query_body):
            src = hit.get("_source") or {}
            src["_index"] = hit.get("_index")
            source_name = (src.get("metadata") or {}).get("source") or src.get("source") or ""
            if source_name not in groups:
                groups[source_name] = new_group(src, hit.get("_id"))
            add_chunk(groups[source_name], src)

        for source_name in batch:
            group = groups.get(source_name)
            if group and group["chunk_count"] > 0:
                doc_id, body = build_doc_body(group)
                if doc_id:
                    is_repair = source_name not in missing_set
                    bulk.add(doc_id, body, is_repair=is_repair)
                    written += 1
                    seen += group["chunk_count"]
                    if written % 200 == 0:
                        print(f"  processed {written}/{total_to_process} docs "
                              f"({written * 100 // total_to_process}%)")
            processed.add(source_name)

        # Checkpoint after each batch
        save_checkpoint({
            "mode": "reconcile",
            "all_sources": None,  # Don't persist huge list here; saved in reconcile()
            "processed_sources": sorted(processed),
            "written": written,
        })
        print_progress(written, total_to_process, seen)
        rate_limit()

    bulk.flush()
    clear_checkpoint()
    # Final progress report
    elapsed = time.time() - _progress_start if _progress_start else 0
    throughput = written / elapsed if elapsed > 0 else 0
    print(f"[DocSearch] reconcile done. written={written}, chunks_scanned={seen}, "
          f"throughput={throughput:.0f} docs/s, elapsed={int(elapsed)}s, dry_run={DRY_RUN}")
    return written


def reconcile():
    """Reconcile kb_doc_search against kb_document*: fill missing + repair stale documents."""
    ensure_doc_search_index()

    # Step 1: get all unique sources from kb_document*
    print("[DocSearch] reconcile: scanning kb_document* for unique sources...")
    all_sources = get_source_list()
    print(f"[DocSearch] reconcile: found {len(all_sources)} unique source documents in kb_document*")

    # Step 2: get existing sources in kb_doc_search
    print("[DocSearch] reconcile: scanning kb_doc_search for existing documents...")
    existing_sources = get_existing_sources()
    print(f"[DocSearch] reconcile: found {len(existing_sources)} documents in kb_doc_search")

    # Step 3: compute missing
    missing = all_sources - existing_sources
    print(f"[DocSearch] reconcile: {len(missing)} documents missing from kb_doc_search")

    if missing:
        sample = sorted(missing)[:20]
        for s in sample:
            print(f"  [MISSING] {s}")
        if len(missing) > 20:
            print(f"  ... and {len(missing) - 20} more")

    # Step 4: find stale documents (existing but with empty required fields)
    print("[DocSearch] reconcile: scanning for stale documents with empty fields...")
    stale = get_stale_sources()
    # Only include stale sources that actually exist in kb_document* (exclude sources that were deleted)
    stale = stale & all_sources
    print(f"[DocSearch] reconcile: {len(stale)} existing documents have empty required fields")

    if stale:
        sample = sorted(stale)[:20]
        for s in sample:
            print(f"  [STALE] {s}")
        if len(stale) > 20:
            print(f"  ... and {len(stale) - 20} more")

    # Step 5: backfill missing + repair stale
    written = backfill_by_sources(missing, stale_sources=stale)

    # Step 6: report
    print(f"[DocSearch] reconcile complete. "
          f"source_total={len(all_sources)}, existing={len(existing_sources)}, "
          f"missing={len(missing)}, stale={len(stale)}, "
          f"processed={written}, dry_run={DRY_RUN}")


def search_batch(pit_id, search_after=None):
    body = {
        "size": BATCH_SIZE,
        "track_total_hits": False,
        "pit": {"id": pit_id, "keep_alive": "5m"},
        "_source": _CHUNK_SOURCE_FIELDS,
        "query": {
            "bool": {
                "must_not": [{"term": {"chunk_granularity": "coarse"}}],
                "filter": _LATEST_FILTER,
            }
        },
        "sort": [
            {"metadata.source": {"order": "asc", "unmapped_type": "keyword"}},
            {"content_hash": {"order": "asc", "unmapped_type": "keyword"}},
            {"_shard_doc": "asc"},
        ],
    }
    if search_after:
        body["search_after"] = search_after
    return es_request("POST", "/_search", body, timeout=180)


def validate():
    """Post-migration validation: count parity, field completeness, _id consistency, duplicates."""
    print("[Validate] Starting post-migration validation...")
    errors = []

    # 1. Document count parity
    print("[Validate] 1/5 Checking document count parity...")
    src_card = get_source_cardinality()
    search_count = es_request("GET", f"/{DOC_SEARCH_READ_ALIAS}/_count").get("count", 0)
    print(f"  kb_document* unique sources: {src_card}")
    print(f"  kb_doc_search documents: {search_count}")
    if src_card != search_count:
        errors.append(f"Count mismatch: sources={src_card}, doc_search={search_count} (diff={search_count - src_card})")

    # 2. Field completeness
    print("[Validate] 2/5 Checking field completeness...")
    for field in ["doc_id", "content_hash", "source"]:
        empty = es_request("POST", f"/{DOC_SEARCH_READ_ALIAS}/_count", {
            "query": {"bool": {"should": [
                {"bool": {"must_not": {"exists": {"field": field}}}},
                {"term": {field: ""}}
            ], "minimum_should_match": 1}}
        }).get("count", 0)
        if empty > 0:
            errors.append(f"Field '{field}' has {empty} empty/missing values")
            print(f"  ❌ {field}: {empty} empty")
        else:
            print(f"  ✅ {field}: all populated")

    # 3. Duplicate sources (is_latest=true should be unique per source)
    print("[Validate] 3/5 Checking for duplicate sources...")
    dup = es_request("POST", f"/{DOC_SEARCH_READ_ALIAS}/_search", {
        "size": 0,
        "query": {"term": {"is_latest": True}},
        "aggs": {"dup": {"terms": {"field": "source", "min_doc_count": 2, "size": 100}}}
    })
    dups = dup.get("aggregations", {}).get("dup", {}).get("buckets", [])
    if dups:
        errors.append(f"Duplicate sources with is_latest=true: {[b['key'] for b in dups[:20]]}")
        for b in dups[:10]:
            print(f"  ❌ duplicate: {b['key']} ({b['doc_count']} records)")
    else:
        print(f"  ✅ No duplicate sources")

    # 4. _id format check (should match md5hex_vN pattern from online pipeline)
    print("[Validate] 4/5 Checking _id format consistency...")
    id_pattern = _re.compile(r"^[a-f0-9]{32}_v\d+$")
    sample = es_request("POST", f"/{DOC_SEARCH_READ_ALIAS}/_search", {
        "size": 200, "_source": ["source"], "sort": "_doc"
    })
    hits = sample.get("hits", {}).get("hits", [])
    bad_ids = [(h["_id"], h.get("_source", {}).get("source", "")) for h in hits
               if not id_pattern.match(h["_id"])]
    if bad_ids:
        errors.append(f"Non-standard _id format: {len(bad_ids)}/{len(hits)} in sample")
        for bid, src in bad_ids[:10]:
            print(f"  ❌ _id={bid} (source={src})")
    else:
        print(f"  ✅ All {len(hits)} sampled _ids match md5hex_vN pattern")

    # 5. Spot check: verify _id matches expected md5(source)_v{version}
    print("[Validate] 5/5 Spot-checking _id correctness...")
    mismatch_count = 0
    for h in hits[:50]:
        src = h.get("_source", {}).get("source", "")
        if not src:
            continue
        # We can't compute expected _id without version, but we can verify the hash prefix
        expected_hash = hashlib.md5(src.encode("utf-8")).hexdigest()
        if not h["_id"].startswith(expected_hash):
            mismatch_count += 1
            if mismatch_count <= 5:
                print(f"  ❌ _id hash mismatch: _id={h['_id']} source={src} expected_prefix={expected_hash}")
    if mismatch_count:
        errors.append(f"_id hash mismatch: {mismatch_count}/50 spot-checked")
    else:
        print(f"  ✅ All spot-checked _ids have correct hash prefix")

    # Summary
    if errors:
        print(f"\n❌ Validation FAILED ({len(errors)} issues):")
        for e in errors:
            print(f"  - {e}")
    else:
        print(f"\n✅ Validation PASSED: {search_count} documents, all 5 checks green")
    return errors


def wait_for_es_and_source_index(timeout=300):
    start_time = time.time()
    print(f"[DocSearch] 开始检测 ES 服务可达性 ({ES_HOST}) 与源索引 {SOURCE_INDEX}...")
    while True:
        try:
            # 1. 尝试连接并获取 ES 基本信息
            es_request("GET", "/")
            
            # 2. 检查源索引是否存在
            try:
                es_request("HEAD", f"/{SOURCE_INDEX}")
                print(f"[DocSearch] ES 服务及源索引 {SOURCE_INDEX} 已就绪，启动迁移补全。")
                return True
            except Exception as e:
                # 404 代表 ES 已启动，但 Java 后端尚未上传文档并创建 kb_document_* 物理索引
                if "404" in str(e):
                    print(f"[DocSearch] 警告: ES 已连通，但源物理索引 {SOURCE_INDEX} 尚未创建，等待中...")
                else:
                    raise e
        except Exception as exc:
            print(f"[DocSearch] ES 服务未就绪，重试中... 错误: {exc}")
        
        if time.time() - start_time > timeout:
            print(f"[DocSearch] 错误: 等待 ES / 源索引就绪超时 ({timeout}秒)，迁移任务终止。")
            return False
        time.sleep(5)


def main():
    mode = BACKFILL_MODE
    print(f"[DocSearch] mode={mode}, dry_run={DRY_RUN}, source_index={SOURCE_INDEX}")

    # Validate mode doesn't need ES wait
    if mode == "validate":
        errors = validate()
        sys.exit(1 if errors else 0)

    # Wait for ES and source index readiness
    if not wait_for_es_and_source_index(timeout=300):
        return

    if mode == "reconcile":
        reconcile()
    else:
        # full mode: PIT-based full backfill with BulkWriter + checkpoint
        ensure_doc_search_index()

        # Get total doc count for progress tracking
        total_docs = get_source_cardinality()
        print(f"[DocSearch] full mode: ~{total_docs} unique documents expected")

        # Resume from checkpoint
        search_after = None
        written = 0
        seen = 0
        cp = load_checkpoint()
        if cp and cp.get("mode") == "full" and cp.get("search_after"):
            search_after = cp["search_after"]
            written = cp.get("written", 0)
            seen = cp.get("seen", 0)
            print(f"[DocSearch] checkpoint: resuming from seen={seen}, written={written}")

        pit_id = open_pit()
        current = None
        bulk = BulkWriter(max_batch=1000)
        try:
            while True:
                resp = search_batch(pit_id, search_after)
                hits = resp.get("hits", {}).get("hits", [])
                if not hits:
                    break
                for hit in hits:
                    src = hit.get("_source") or {}
                    src["_index"] = hit.get("_index")
                    key = group_key_for(src, hit.get("_id"))
                    if current and key != current["group_key"]:
                        doc_id, body = build_doc_body(current)
                        if doc_id:
                            bulk.add(doc_id, body)
                            written += 1
                        current = None
                    if current is None:
                        current = new_group(src, hit.get("_id"))
                    add_chunk(current, src)
                    seen += 1
                search_after = hits[-1].get("sort")
                print_progress(written, total_docs, seen)
                if seen % (BATCH_SIZE * 10) == 0:
                    save_checkpoint({
                        "mode": "full",
                        "search_after": search_after,
                        "seen": seen,
                        "written": written,
                    })
            if current:
                doc_id, body = build_doc_body(current)
                if doc_id:
                    bulk.add(doc_id, body)
                    written += 1
        finally:
            bulk.flush()
            close_pit(pit_id)
        clear_checkpoint()
        # Final report with timing
        elapsed = time.time() - _progress_start if _progress_start else 0
        throughput = written / elapsed if elapsed > 0 else 0
        print(f"[DocSearch] backfill done. scanned_chunks={seen}, written_docs={written}, "
              f"throughput={throughput:.0f} docs/s, elapsed={int(elapsed)}s, dry_run={DRY_RUN}")


if __name__ == "__main__":
    main()
