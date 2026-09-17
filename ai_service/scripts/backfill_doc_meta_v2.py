"""
Backfill document-level vectors into kb_doc_meta_v2.

The script reuses existing chunk vectors from kb_document_* indexes. It does not
run embedding again. It streams fine chunks sorted by source, mean-pools vectors
per document, L2-normalizes the result, and writes one doc-meta record.
"""

import base64
import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from core.normalization.text_normalizer import (  # noqa: E402
    normalize_content_if_fully_encoded,
    normalize_filename,
    normalize_metadata_text,
)


ES_HOST = os.getenv("ES_HOST", "http://localhost:9200").rstrip("/")
ES_USER = os.getenv("ES_USER", os.getenv("ES_USERNAME", ""))
ES_PASS = os.getenv("ES_PASS", os.getenv("ES_PASSWORD", ""))
SOURCE_INDEX = os.getenv("SOURCE_INDEX", "kb_document_*")
DOC_META_INDEX = os.getenv("KB_DOC_META_INDEX", "kb_doc_meta_v2")
DOC_META_READ_ALIAS = os.getenv("KB_DOC_META_READ_ALIAS", "kb_doc_meta_read")
DOC_META_WRITE_ALIAS = os.getenv("KB_DOC_META_WRITE_ALIAS", "kb_doc_meta_write")
BATCH_SIZE = int(os.getenv("DOC_META_BACKFILL_BATCH_SIZE", "200"))
VECTOR_DIM = int(os.getenv("DOC_VECTOR_DIM", "1024"))
DEFAULT_ACL_TOKENS = [
    token.strip()
    for token in os.getenv("KB_DOC_META_DEFAULT_ACL_TOKENS", "_INTERNAL").split(",")
    if token.strip()
] or ["_INTERNAL"]


def env_int(name: str, default: int, min_value: int = 0) -> int:
    """
    业务功能：读取 doc_meta 回填脚本的整数配置。
    关键流程：离线回填可能先于服务 init 创建索引，必须允许通过环境变量控制分片和副本。
    """
    raw = os.getenv(name)
    if raw is None or str(raw).strip() == "":
        return default
    try:
        value = int(str(raw).strip())
    except ValueError:
        print(f"[DocMeta] env {name}={raw!r} is not an integer, fallback to {default}")
        return default
    if value < min_value:
        print(f"[DocMeta] env {name}={value} is lower than {min_value}, fallback to {default}")
        return default
    return value


def doc_meta_index_settings() -> dict:
    """
    业务功能：生成 kb_doc_meta_v2 回填目标索引 settings。
    关键流程：复用 KB_DOC_META_* 命名约定，保证回填脚本与服务 init 的容量规划一致。
    """
    return {
        "number_of_shards": env_int("KB_DOC_META_SHARDS", 1, min_value=1),
        "number_of_replicas": env_int("KB_DOC_META_REPLICAS", 0, min_value=0),
    }


def es_request(method, path, body=None):
    url = ES_HOST + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"}
    if ES_USER:
        token = base64.b64encode(f"{ES_USER}:{ES_PASS}".encode("utf-8")).decode("ascii")
        headers["Authorization"] = f"Basic {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            payload = json.loads(raw)
        except Exception:
            payload = {"error": raw}
        raise RuntimeError(f"ES {method} {path} failed: {e.code} {payload}")


def ensure_doc_meta_index():
    try:
        es_request("HEAD", f"/{DOC_META_INDEX}")
        exists = True
    except Exception:
        exists = False
    if not exists:
        mapping = {
            "settings": doc_meta_index_settings(),
            "mappings": {
                "properties": {
                    "doc_id": {"type": "keyword"},
                    "doc_version": {"type": "integer"},
                    "content_hash": {"type": "keyword"},
                    "source": {"type": "keyword"},
                    "source_name": {"type": "keyword"},
                    "title": {
                        "type": "text",
                        "fields": {"keyword": {"type": "keyword", "ignore_above": 256}},
                    },
                    "summary": {"type": "text", "index": False},
                    "doc_type": {"type": "keyword"},
                    "data_source": {"type": "keyword"},
                    "chunk_count": {"type": "integer"},
                    "is_latest": {"type": "boolean"},
                    "acl_tokens": {"type": "keyword"},
                    "visibility": {"type": "keyword"},
                    "owner_dept_id": {"type": "keyword"},
                    "updated_at": {"type": "date", "format": "epoch_millis"},
                    "doc_vector": {
                        "type": "dense_vector",
                        "dims": VECTOR_DIM,
                        "index": True,
                        "similarity": "cosine",
                    },
                }
            },
        }
        es_request("PUT", f"/{DOC_META_INDEX}", mapping)
        print(f"[DocMeta] created index {DOC_META_INDEX}")

    actions = []
    alias_resp = es_request("GET", f"/{DOC_META_INDEX}/_alias", None)
    aliases = alias_resp.get(DOC_META_INDEX, {}).get("aliases", {})
    for alias in (DOC_META_READ_ALIAS, DOC_META_WRITE_ALIAS):
        try:
            alias_refs = es_request("GET", f"/_alias/{alias}", None)
        except Exception:
            alias_refs = {}
        for index_name in alias_refs.keys():
            if index_name != DOC_META_INDEX:
                actions.append({"remove": {"index": index_name, "alias": alias}})
    if DOC_META_READ_ALIAS not in aliases:
        actions.append({"add": {"index": DOC_META_INDEX, "alias": DOC_META_READ_ALIAS}})
    if aliases.get(DOC_META_WRITE_ALIAS, {}).get("is_write_index") is not True:
        if DOC_META_WRITE_ALIAS in aliases:
            actions.append({"remove": {"index": DOC_META_INDEX, "alias": DOC_META_WRITE_ALIAS}})
        actions.append({
            "add": {
                "index": DOC_META_INDEX,
                "alias": DOC_META_WRITE_ALIAS,
                "is_write_index": True,
            }
        })
    if actions:
        es_request("POST", "/_aliases", {"actions": actions})
        print(f"[DocMeta] aliases registered: {DOC_META_READ_ALIAS}, {DOC_META_WRITE_ALIAS}")


def open_pit():
    resp = es_request("POST", f"/{SOURCE_INDEX}/_pit?keep_alive=5m")
    pit_id = resp.get("id")
    if not pit_id:
        raise RuntimeError(f"open PIT failed: {resp}")
    return pit_id


def close_pit(pit_id):
    if not pit_id:
        return
    try:
        es_request("DELETE", "/_pit", {"id": pit_id})
    except Exception as exc:
        print(f"[DocMeta] close PIT skipped: {exc}", file=sys.stderr)


def normalize(vec):
    norm = math.sqrt(sum(v * v for v in vec))
    if norm <= 0:
        return vec
    return [v / norm for v in vec]


def meta_id(doc_id, content_hash, source):
    raw = doc_id or content_hash or source
    return urllib.parse.quote(raw, safe="")


def group_key_for(src, hit_id):
    meta = src.get("metadata") or {}
    return meta.get("doc_id") or src.get("doc_id") \
        or meta.get("content_hash") or src.get("content_hash") \
        or meta.get("source") or src.get("source") or hit_id


def physical_index(index_name):
    index_name = (index_name or "").strip()
    if index_name.endswith("_write"):
        return index_name[:-len("_write")]
    return index_name


def index_code(index_name):
    index_name = physical_index(index_name)
    prefix = "kb_document_"
    return index_name[len(prefix):] if index_name.startswith(prefix) else index_name


def write_doc_meta(group):
    body = build_doc_meta_body(group)
    if body is None:
        return
    doc_id = meta_id(body["doc_id"], body["content_hash"], body["source"])
    es_request("PUT", f"/{DOC_META_WRITE_ALIAS}/_doc/{doc_id}", body)


def build_doc_meta_body(group):
    if not group or group["count"] <= 0:
        return None
    mean_vec = [v / group["count"] for v in group["sum"]]
    source = normalize_filename(group["source"])
    title = normalize_metadata_text(group.get("title") or source)
    summary = normalize_content_if_fully_encoded(group.get("summary") or "")
    stable_doc_id = group.get("doc_id") or group.get("group_key") or source
    body = {
        "doc_id": stable_doc_id,
        "doc_version": group.get("doc_version"),
        "content_hash": group.get("content_hash") or "",
        "source": source,
        "source_name": source,
        "title": title,
        "summary": summary,
        "doc_type": group.get("doc_type") or "",
        "data_source": group.get("data_source") or "document",
        "chunk_count": group["count"],
        "is_latest": True,
        "acl_tokens": sorted(group.get("acl_tokens") or set(DEFAULT_ACL_TOKENS)),
        "visibility": group.get("visibility") or "",
        "owner_dept_id": group.get("owner_dept_id") or "",
        "source_index": group.get("source_index") or "",
        "index_code": index_code(group.get("source_index") or ""),
        "owner_unit_code": group.get("owner_dept_id") or "",
        "visible_unit_codes": [group.get("owner_dept_id")] if group.get("owner_dept_id") else [],
        "permission_version": int(time.time() * 1000),
        "updated_at": int(time.time() * 1000),
        "doc_vector": normalize(mean_vec),
    }
    return body


def new_group(source, src):
    meta = src.get("metadata") or {}
    source = normalize_filename(source)
    content = normalize_content_if_fully_encoded(src.get("content") or "").strip()
    return {
        "source": source,
        "group_key": group_key_for(src, source),
        "sum": [0.0] * VECTOR_DIM,
        "count": 0,
        "doc_id": meta.get("doc_id") or src.get("doc_id") or "",
        "doc_version": meta.get("doc_version") or src.get("doc_version"),
        "content_hash": meta.get("content_hash") or src.get("content_hash") or "",
        "title": normalize_metadata_text(meta.get("title") or src.get("title") or source),
        "summary": content[:500],
        "doc_type": meta.get("doc_type") or "",
        "data_source": meta.get("data_source") or "document",
        "acl_tokens": set(meta.get("acl_tokens") or src.get("acl_tokens") or DEFAULT_ACL_TOKENS),
        "visibility": meta.get("visibility") or "",
        "owner_dept_id": meta.get("owner_dept_id") or "",
        "source_index": physical_index(src.get("_index") or ""),
    }


def add_chunk(group, src):
    vec = src.get("vector")
    if not isinstance(vec, list) or len(vec) != VECTOR_DIM:
        return False
    for i, value in enumerate(vec):
        group["sum"][i] += float(value)
    group["count"] += 1
    meta = src.get("metadata") or {}
    for token in meta.get("acl_tokens") or src.get("acl_tokens") or []:
        group["acl_tokens"].add(str(token))
    return True


def run():
    ensure_doc_meta_index()
    pit_id = open_pit()
    search_after = None
    current = None
    written = 0
    chunks = 0

    try:
        while True:
            query = {
                "size": BATCH_SIZE,
                "track_total_hits": False,
                "pit": {"id": pit_id, "keep_alive": "5m"},
                "_source": [
                    "vector",
                    "content",
                    "acl_tokens",
                    "metadata.source",
                    "metadata.doc_id",
                    "metadata.doc_version",
                    "metadata.content_hash",
                    "metadata.title",
                    "metadata.doc_type",
                    "metadata.data_source",
                    "metadata.acl_tokens",
                    "metadata.visibility",
                    "metadata.owner_dept_id",
                    "doc_id",
                    "doc_version",
                    "content_hash",
                    "source",
                ],
                "query": {
                    "bool": {
                        "filter": [
                            {"term": {"chunk_granularity": "fine"}},
                            {
                                "bool": {
                                    "should": [
                                        {"term": {"metadata.is_latest": True}},
                                        {"bool": {"must_not": [{"exists": {"field": "metadata.is_latest"}}]}},
                                    ],
                                    "minimum_should_match": 1,
                                }
                            },
                        ]
                    }
                },
                "sort": [
                    {"metadata.doc_id": {"order": "asc", "missing": "_last", "unmapped_type": "keyword"}},
                    {"metadata.content_hash": {"order": "asc", "missing": "_last", "unmapped_type": "keyword"}},
                    {"metadata.source": {"order": "asc", "missing": "_last", "unmapped_type": "keyword"}},
                    {"_shard_doc": "asc"},
                ],
            }
            if search_after:
                query["search_after"] = search_after

            resp = es_request("POST", "/_search", query)
            pit_id = resp.get("pit_id", pit_id)
            hits = resp.get("hits", {}).get("hits", [])
            if not hits:
                break

            for hit in hits:
                src = hit.get("_source") or {}
                src["_index"] = hit.get("_index")
                meta = src.get("metadata") or {}
                source = meta.get("source") or src.get("source") or hit.get("_id")
                group_key = group_key_for(src, hit.get("_id"))
                if current is None or current["group_key"] != group_key:
                    if current is not None:
                        write_doc_meta(current)
                        written += 1
                        if written % 100 == 0:
                            print(f"[DocMeta] written={written} chunks={chunks}")
                    current = new_group(source, src)
                if add_chunk(current, src):
                    chunks += 1
            search_after = hits[-1].get("sort")
    finally:
        close_pit(pit_id)

    if current is not None:
        write_doc_meta(current)
        written += 1
    es_request("POST", f"/{DOC_META_INDEX}/_refresh")
    print(f"[DocMeta] backfill complete: docs={written}, chunks={chunks}")


if __name__ == "__main__":
    try:
        run()
    except Exception as exc:
        print(f"[DocMeta] backfill failed: {exc}", file=sys.stderr)
        sys.exit(1)
