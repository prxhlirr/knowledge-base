"""
诊断 kb_doc_search 回填不一致问题。
新增: 字段完整性检查 + _id 格式校验 + 重复 source 检测。

用法: ES_HOST=http://192.168.74.1:9200 ES_USER=admin ES_PASS=8732391 python -u scripts/diag_backfill.py
"""
import base64, hashlib, json, os, re, sys, urllib.request, urllib.error

ES_HOST = os.getenv("ES_HOST", "http://localhost:9200").rstrip("/")
ES_USER = os.getenv("ES_USER", "")
ES_PASS = os.getenv("ES_PASS", "")
SOURCE_INDEX = os.getenv("SOURCE_INDEX", "kb_document_*")
DOC_SEARCH_ALIAS = os.getenv("KB_DOC_SEARCH_READ_ALIAS", "kb_doc_search")


def es_get(path):
    headers = {"Content-Type": "application/json"}
    if ES_USER:
        token = base64.b64encode(f"{ES_USER}:{ES_PASS}".encode()).decode()
        headers["Authorization"] = f"Basic {token}"
    req = urllib.request.Request(ES_HOST + path, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))


def es_post(path, body):
    headers = {"Content-Type": "application/json"}
    if ES_USER:
        token = base64.b64encode(f"{ES_USER}:{ES_PASS}".encode()).decode()
        headers["Authorization"] = f"Basic {token}"
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(ES_HOST + path, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def scroll_all(path, body):
    """Scroll through all results."""
    results = []
    scroll_id = None
    try:
        while True:
            if scroll_id:
                resp = es_post("/_search/scroll", {"scroll_id": scroll_id, "scroll": "2m"})
            else:
                resp = es_post(f"{path}?scroll=2m", body)
            hits = resp.get("hits", {}).get("hits", [])
            if not hits:
                break
            results.extend(hits)
            scroll_id = resp.get("_scroll_id")
    finally:
        if scroll_id:
            try:
                es_post("/_search/scroll", {"scroll_id": scroll_id, "scroll": "2m"})
            except Exception:
                pass
    return results


def main():
    src_idx = SOURCE_INDEX

    # 1. is_latest=true 中 fine vs coarse 分布
    r = es_post(f"/{src_idx}/_search", {
        "size": 0,
        "query": {"term": {"metadata.is_latest": True}},
        "aggs": {
            "by_granularity": {"terms": {"field": "chunk_granularity", "size": 10}}
        }
    })
    print("===== 1. is_latest=true chunks 按 granularity 分布 =====")
    for b in r["aggregations"]["by_granularity"]["buckets"]:
        print(f"  {b['key']}: {b['doc_count']} chunks")

    # 2. is_latest=true fine chunks 按 source 去重
    r2 = es_post(f"/{src_idx}/_search", {
        "size": 0,
        "query": {"bool": {"must": [
            {"term": {"metadata.is_latest": True}},
            {"term": {"chunk_granularity": "fine"}}
        ]}},
        "aggs": {
            "unique_sources": {"cardinality": {"field": "metadata.source"}},
            "sources": {
                "terms": {"field": "metadata.source", "size": 200},
                "aggs": {
                    "by_gran": {"terms": {"field": "chunk_granularity", "size": 5}},
                    "versions": {"terms": {"field": "metadata.doc_version", "size": 5}}
                }
            }
        }
    })
    print(f"\n===== 2. is_latest=true + fine 独立文档数 (by source): {r2['aggregations']['unique_sources']['value']} =====")
    for b in r2["aggregations"]["sources"]["buckets"]:
        grans = ", ".join(f"{g['key']}={g['doc_count']}" for g in b["by_gran"]["buckets"])
        vers = ", ".join(f"v{v['key']}={v['doc_count']}" for v in b["versions"]["buckets"]) if b["versions"]["buckets"] else "no-version"
        print(f"  {b['key']} | total_fine={b['doc_count']} | {grans} | {vers}")

    # 3. kb_doc_search 已有文档列表 + _id 格式检查
    all_ds_docs = scroll_all(f"/{DOC_SEARCH_ALIAS}/_search", {
        "size": 1000,
        "_source": ["source", "is_latest", "doc_version", "chunk_count", "doc_id", "content_hash"],
        "query": {"match_all": {}}
    })
    print(f"\n===== 3. kb_doc_search 已有文档: {len(all_ds_docs)} =====")

    id_pattern = re.compile(r"^[a-f0-9]{32}_v\d+$")
    bad_ids = []
    for h in all_ds_docs[:50]:
        s = h["_source"]
        mark = "✅" if id_pattern.match(h["_id"]) else "❌"
        if not id_pattern.match(h["_id"]):
            bad_ids.append(h["_id"])
        print(f"  {mark} _id={h['_id']}, source={s.get('source')}, doc_id={s.get('doc_id')}, "
              f"is_latest={s.get('is_latest')}, doc_version={s.get('doc_version')}, chunks={s.get('chunk_count')}")

    if bad_ids:
        print(f"\n  ⚠️ 发现 {len(bad_ids)}/{min(50, len(all_ds_docs))} 个非标准 _id 格式（应为 md5hex_vN）")
        for bid in bad_ids[:10]:
            print(f"    {bid}")

    # 4. 对比: 在源中有但 kb_doc_search 中没有的文档
    src_names = set(b["key"] for b in r2["aggregations"]["sources"]["buckets"])
    ds_names = set(h["_source"].get("source", "") for h in all_ds_docs)
    missing = src_names - ds_names
    print(f"\n===== 4. 源索引有但 kb_doc_search 缺失的文档: {len(missing)} =====")
    for name in sorted(missing)[:30]:
        print(f"  ❌ {name}")
    if len(missing) > 30:
        print(f"  ... and {len(missing) - 30} more")

    # 5. 检查 is_latest=true 的 fine chunk 是否有 doc_id 字段
    r5 = es_post(f"/{src_idx}/_search", {
        "size": 5,
        "query": {"bool": {"must": [
            {"term": {"metadata.is_latest": True}},
            {"term": {"chunk_granularity": "fine"}}
        ]}},
        "_source": ["metadata.source", "metadata.doc_id", "metadata.doc_version", "content_hash", "chunk_granularity"]
    })
    print(f"\n===== 5. is_latest=true fine chunk 样本 (检查 doc_id) =====")
    for h in r5["hits"]["hits"]:
        s = h["_source"]
        m = s.get("metadata", {})
        print(f"  _id={h['_id']}, source={m.get('source')}, doc_id={m.get('doc_id')}, "
              f"doc_version={m.get('doc_version')}, content_hash={str(s.get('content_hash',''))[:16]}..., gran={s.get('chunk_granularity')}")

    # 6. 额外检查: 用回填脚本的相同查询条件看能扫到多少 chunk
    r6 = es_post(f"/{src_idx}/_search", {
        "size": 0,
        "query": {
            "bool": {
                "must_not": [{"term": {"chunk_granularity": "coarse"}}],
                "filter": [
                    {"bool": {"should": [
                        {"term": {"metadata.is_latest": True}},
                        {"bool": {"must_not": {"exists": {"field": "metadata.is_latest"}}}}
                    ], "minimum_should_match": 1}}
                ]
            }
        }
    })
    print(f"\n===== 6. 回填脚本相同过滤条件的 chunk 数: {r6['hits']['total']['value']} =====")

    # 7. [新增] 字段完整性检查: kb_doc_search 中有多少文档缺少关键字段
    print(f"\n===== 7. kb_doc_search 字段完整性检查 =====")
    for field in ["doc_id", "content_hash", "source"]:
        try:
            empty_count = es_post(f"/{DOC_SEARCH_ALIAS}/_count", {
                "query": {"bool": {"should": [
                    {"bool": {"must_not": {"exists": {"field": field}}}},
                    {"term": {field: ""}}
                ], "minimum_should_match": 1}}
            }).get("count", 0)
            mark = "✅" if empty_count == 0 else "❌"
            print(f"  {mark} {field}: {empty_count} empty/missing")
        except Exception as e:
            print(f"  ⚠️ {field}: check failed ({e})")

    # 8. [新增] 重复 source 检测 (is_latest=true 应唯一)
    print(f"\n===== 8. 重复 source 检测 (is_latest=true) =====")
    try:
        dup_resp = es_post(f"/{DOC_SEARCH_ALIAS}/_search", {
            "size": 0,
            "query": {"term": {"is_latest": True}},
            "aggs": {"dup": {"terms": {"field": "source", "min_doc_count": 2, "size": 50}}}
        })
        dups = dup_resp.get("aggregations", {}).get("dup", {}).get("buckets", [])
        if dups:
            print(f"  ❌ 发现 {len(dups)} 个重复 source:")
            for b in dups[:20]:
                print(f"    {b['key']} → {b['doc_count']} 条记录")
        else:
            print(f"  ✅ 无重复 source")
    except Exception as e:
        print(f"  ⚠️ 重复检测失败: {e}")

    # 9. [新增] _id hash 一致性 spot-check
    print(f"\n===== 9. _id hash 一致性 spot-check =====")
    mismatch = 0
    checked = 0
    for h in all_ds_docs[:100]:
        src = h.get("_source", {}).get("source", "")
        if not src:
            continue
        checked += 1
        expected_hash = hashlib.md5(src.encode("utf-8")).hexdigest()
        if not h["_id"].startswith(expected_hash):
            mismatch += 1
            if mismatch <= 5:
                print(f"  ❌ _id={h['_id']} expected_prefix={expected_hash} source={src}")
    if checked > 0:
        if mismatch == 0:
            print(f"  ✅ All {checked} spot-checked _ids have correct hash prefix")
        else:
            print(f"  ❌ {mismatch}/{checked} _ids have mismatched hash prefix")


if __name__ == "__main__":
    main()
