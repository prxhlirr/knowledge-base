"""
Diagnose URL-encoded Chinese pollution in searchable ES fields.

Read-only. The report classifies pollution depth:
- Class A: kb_doc_search only
- Class B: kb_document metadata polluted, content/display_content clean
- Class C: kb_document content/display_content polluted
"""

import base64
import json
import os
import re
import urllib.request

ES_HOST = os.getenv("ES_HOST", "http://localhost:9200").rstrip("/")
ES_USER = os.getenv("ES_USER", os.getenv("ES_USERNAME", ""))
ES_PASS = os.getenv("ES_PASS", os.getenv("ES_PASSWORD", ""))
SOURCE_INDEX = os.getenv("SOURCE_INDEX", "kb_document_*")
DOC_SEARCH_INDEX = os.getenv("KB_DOC_SEARCH_READ_ALIAS", "kb_doc_search")
DOC_META_INDEX = os.getenv("KB_DOC_META_READ_ALIAS", "kb_doc_meta")
SAMPLE_SIZE = int(os.getenv("POLLUTION_SAMPLE_SIZE", "20"))
FAIL_ON_POLLUTION = os.getenv("FAIL_ON_POLLUTION", "false").lower() == "true"

ENCODED_CHINESE_RE = re.compile(r"(?i)%E[0-9A-F](?:%[0-9A-F]{2}){2}")

TARGETS = [
    {
        "name": "kb_document",
        "index": SOURCE_INDEX,
        "fields": ["metadata.source", "metadata.title", "content", "display_content"],
        "content_fields": ["content", "display_content"],
    },
    {
        "name": "kb_doc_search",
        "index": DOC_SEARCH_INDEX,
        "fields": ["source", "source_name", "title", "doc_terms", "summary"],
        "content_fields": ["doc_terms", "summary"],
    },
    {
        "name": "kb_doc_meta",
        "index": DOC_META_INDEX,
        "fields": ["source", "source_name", "title", "summary"],
        "content_fields": ["summary"],
    },
]


def es_request(method, path, body=None, timeout=120):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"}
    if ES_USER:
        token = base64.b64encode(f"{ES_USER}:{ES_PASS}".encode("utf-8")).decode("ascii")
        headers["Authorization"] = f"Basic {token}"
    req = urllib.request.Request(ES_HOST + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def get_path(doc, dotted):
    current = doc
    for part in dotted.split("."):
        if not isinstance(current, dict):
            return None
        current = current.get(part)
    return current


def value_has_encoded_chinese(value):
    if value is None:
        return False
    if isinstance(value, (list, tuple, set)):
        return any(value_has_encoded_chinese(item) for item in value)
    return bool(ENCODED_CHINESE_RE.search(str(value)))


def polluted_fields(source, fields):
    return [field for field in fields if value_has_encoded_chinese(get_path(source, field))]


def search_candidates(index, fields):
    query = " OR ".join('"{}"'.format(token) for token in ("%E5", "%E6", "%E7", "%E8", "%E9"))
    body = {
        "size": SAMPLE_SIZE,
        "_source": fields,
        "query": {
            "query_string": {
                "query": query,
                "fields": fields,
                "default_operator": "OR",
            }
        },
    }
    count_body = {"query": body["query"]}
    count = es_request("POST", f"/{index}/_count", count_body).get("count", 0)
    hits = es_request("POST", f"/{index}/_search", body).get("hits", {}).get("hits", [])
    return count, hits


def diagnose_target(target):
    try:
        count, hits = search_candidates(target["index"], target["fields"])
    except Exception as exc:
        return {"name": target["name"], "index": target["index"], "error": str(exc)}

    samples = []
    content_polluted = 0
    metadata_polluted = 0
    for hit in hits:
        source = hit.get("_source") or {}
        fields = polluted_fields(source, target["fields"])
        content_fields = polluted_fields(source, target["content_fields"])
        if content_fields:
            content_polluted += 1
        elif fields:
            metadata_polluted += 1
        samples.append({
            "_index": hit.get("_index"),
            "_id": hit.get("_id"),
            "polluted_fields": fields,
            "content_polluted": bool(content_fields),
            "_source": source,
        })

    return {
        "name": target["name"],
        "index": target["index"],
        "candidate_count": count,
        "sample_count": len(samples),
        "sample_metadata_only_polluted": metadata_polluted,
        "sample_content_polluted": content_polluted,
        "samples": samples,
    }


def classify(report):
    by_name = {item.get("name"): item for item in report if "error" not in item}
    doc = by_name.get("kb_document", {})
    doc_search = by_name.get("kb_doc_search", {})
    if doc.get("sample_content_polluted", 0) > 0:
        return "Class C: kb_document content/display_content polluted; re-ingest affected documents."
    if doc.get("candidate_count", 0) > 0:
        return "Class B: kb_document metadata polluted; repair metadata and related MySQL source_name mappings, then rebuild kb_doc_search."
    if doc_search.get("candidate_count", 0) > 0:
        return "Class A: only doc_search/doc_meta appears polluted; rebuild kb_doc_search from clean source."
    return "Clean: no obvious URL-encoded Chinese pollution found in sampled searchable fields."


def main():
    report = [diagnose_target(target) for target in TARGETS]
    output = {"classification": classify(report), "targets": report}
    print(json.dumps(output, ensure_ascii=False, indent=2))
    total = sum(item.get("candidate_count", 0) for item in report if isinstance(item, dict))
    if FAIL_ON_POLLUTION and total > 0:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
