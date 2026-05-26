"""
Validate kb_doc_meta_v2 after backfill or an index-alias switch.

Checks are intentionally read-only:
- read/write aliases exist
- doc_vector mapping is dense_vector with the expected dimension
- latest records have doc_vector and acl_tokens
- no obvious duplicate latest records by doc_id/source_name
- reports source chunk distinct counts for manual reconciliation
"""

import base64
import json
import os
import sys
import urllib.error
import urllib.request


ES_HOST = os.getenv("ES_HOST", "http://localhost:9200").rstrip("/")
ES_USER = os.getenv("ES_USER", os.getenv("ES_USERNAME", ""))
ES_PASS = os.getenv("ES_PASS", os.getenv("ES_PASSWORD", ""))
SOURCE_INDEX = os.getenv("SOURCE_INDEX", "kb_document_*")
DOC_META_INDEX = os.getenv("KB_DOC_META_INDEX", "kb_doc_meta_v2")
DOC_META_READ_ALIAS = os.getenv("KB_DOC_META_READ_ALIAS", "kb_doc_meta_read")
DOC_META_WRITE_ALIAS = os.getenv("KB_DOC_META_WRITE_ALIAS", "kb_doc_meta_write")
VECTOR_DIM = int(os.getenv("DOC_VECTOR_DIM", "1024"))


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
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8")
        try:
            payload = json.loads(raw)
        except Exception:
            payload = {"error": raw}
        raise RuntimeError(f"ES {method} {path} failed: {exc.code} {payload}")


def count(index, query):
    resp = es_request("POST", f"/{index}/_count", {"query": query})
    return int(resp.get("count", 0))


def latest_query(extra_filter=None, extra_must_not=None):
    filters = [{"term": {"is_latest": True}}]
    if extra_filter:
        filters.append(extra_filter)
    query = {"bool": {"filter": filters}}
    if extra_must_not:
        query["bool"]["must_not"] = extra_must_not
    return query


def cardinality(index, field):
    try:
        resp = es_request("POST", f"/{index}/_search", {
            "size": 0,
            "track_total_hits": False,
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
            "aggs": {"distinct_docs": {"cardinality": {"field": field, "precision_threshold": 40000}}},
        })
        return int(resp.get("aggregations", {}).get("distinct_docs", {}).get("value", 0))
    except Exception as exc:
        return {"error": str(exc)}


def duplicate_buckets(field):
    resp = es_request("POST", f"/{DOC_META_READ_ALIAS}/_search", {
        "size": 0,
        "query": latest_query(
            {"exists": {"field": field}},
            [{"term": {field: ""}}],
        ),
        "aggs": {
            "dups": {
                "terms": {
                    "field": field,
                    "min_doc_count": 2,
                    "size": 20,
                }
            }
        },
    })
    return resp.get("aggregations", {}).get("dups", {}).get("buckets", [])


def sample_bad_vector_dims():
    resp = es_request("POST", f"/{DOC_META_READ_ALIAS}/_search", {
        "size": 50,
        "_source": ["doc_id", "source_name", "doc_vector"],
        "query": latest_query({"exists": {"field": "doc_vector"}}),
    })
    bad = []
    for hit in resp.get("hits", {}).get("hits", []):
        src = hit.get("_source") or {}
        vec = src.get("doc_vector")
        if not isinstance(vec, list) or len(vec) != VECTOR_DIM:
            bad.append({
                "_id": hit.get("_id"),
                "doc_id": src.get("doc_id"),
                "source_name": src.get("source_name"),
                "dim": len(vec) if isinstance(vec, list) else None,
            })
    return bad


def run():
    failures = []
    warnings = []

    mapping = es_request("GET", f"/{DOC_META_INDEX}/_mapping")
    props = mapping.get(DOC_META_INDEX, {}).get("mappings", {}).get("properties", {})
    vector_mapping = props.get("doc_vector", {})
    if vector_mapping.get("type") != "dense_vector" or int(vector_mapping.get("dims", 0)) != VECTOR_DIM:
        failures.append(f"doc_vector mapping invalid: {vector_mapping}")

    aliases = es_request("GET", f"/{DOC_META_INDEX}/_alias").get(DOC_META_INDEX, {}).get("aliases", {})
    if DOC_META_READ_ALIAS not in aliases:
        failures.append(f"missing read alias: {DOC_META_READ_ALIAS}")
    if DOC_META_WRITE_ALIAS not in aliases:
        failures.append(f"missing write alias: {DOC_META_WRITE_ALIAS}")
    elif aliases[DOC_META_WRITE_ALIAS].get("is_write_index") is not True:
        failures.append(f"write alias is not marked is_write_index=true: {DOC_META_WRITE_ALIAS}")

    latest_count = count(DOC_META_READ_ALIAS, latest_query())
    missing_vector = count(DOC_META_READ_ALIAS, latest_query({"bool": {"must_not": [{"exists": {"field": "doc_vector"}}]}}))
    missing_acl = count(DOC_META_READ_ALIAS, latest_query({"bool": {"must_not": [{"exists": {"field": "acl_tokens"}}]}}))
    blank_doc_id = count(DOC_META_READ_ALIAS, latest_query({"term": {"doc_id": ""}}))
    if latest_count == 0:
        failures.append("no latest doc_meta records found")
    if missing_vector:
        failures.append(f"latest records missing doc_vector: {missing_vector}")
    if missing_acl:
        failures.append(f"latest records missing acl_tokens: {missing_acl}")
    if blank_doc_id:
        failures.append(f"latest records with blank doc_id: {blank_doc_id}")

    bad_dims = sample_bad_vector_dims()
    if bad_dims:
        failures.append(f"sampled doc_vector dimension mismatch: {bad_dims[:5]}")

    dup_doc_id = duplicate_buckets("doc_id")
    dup_source = duplicate_buckets("source_name")
    if dup_doc_id:
        failures.append(f"duplicate latest doc_id buckets: {dup_doc_id[:5]}")
    if dup_source:
        warnings.append(f"duplicate latest source_name buckets: {dup_source[:5]}")

    source_doc_ids = cardinality(SOURCE_INDEX, "metadata.doc_id")
    source_names = cardinality(SOURCE_INDEX, "metadata.source")
    if isinstance(source_doc_ids, dict):
        warnings.append(f"source metadata.doc_id cardinality skipped: {source_doc_ids.get('error')}")
    elif source_doc_ids and abs(source_doc_ids - latest_count) > max(10, source_doc_ids * 0.02):
        warnings.append(f"latest meta count differs from source metadata.doc_id cardinality: meta={latest_count}, source={source_doc_ids}")
    if isinstance(source_names, dict):
        warnings.append(f"source metadata.source cardinality skipped: {source_names.get('error')}")
    elif source_names and abs(source_names - latest_count) > max(10, source_names * 0.02):
        warnings.append(f"latest meta count differs from source metadata.source cardinality: meta={latest_count}, source={source_names}")

    report = {
        "doc_meta_index": DOC_META_INDEX,
        "read_alias": DOC_META_READ_ALIAS,
        "write_alias": DOC_META_WRITE_ALIAS,
        "latest_meta_count": latest_count,
        "source_doc_id_cardinality": source_doc_ids,
        "source_name_cardinality": source_names,
        "failures": failures,
        "warnings": warnings,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(run())
