from elasticsearch import Elasticsearch
es = Elasticsearch("http://localhost:9200")

# 统计
has_kw = es.count(index="kb_document_v1", body={"query": {"exists": {"field": "keywords"}}})
has_latest = es.count(index="kb_document_v1", body={"query": {"exists": {"field": "metadata.is_latest"}}})
total = es.count(index="kb_document_v1")

print(f"total docs: {total['count']}")
print(f"has keywords: {has_kw['count']}")
print(f"has is_latest: {has_latest['count']}")

# 抽一条有 keywords 的
if has_kw["count"] > 0:
    r = es.search(index="kb_document_v1", body={
        "size": 1,
        "query": {"exists": {"field": "keywords"}},
        "_source": ["keywords", "metadata.is_latest", "metadata.source"]
    })
    print("sample with keywords:", r["hits"]["hits"][0]["_source"])

# 抽一条没有 keywords 的
no_kw = es.search(index="kb_document_v1", body={
    "size": 1,
    "query": {"bool": {"must_not": {"exists": {"field": "keywords"}}}},
    "_source": ["metadata.is_latest", "metadata.source", "metadata.chunk_id"]
})
if no_kw["hits"]["hits"]:
    print("sample WITHOUT keywords:", no_kw["hits"]["hits"][0]["_source"])

# 检查 is_latest=false 的数量
expired = es.count(index="kb_document_v1", body={"query": {"term": {"metadata.is_latest": False}}})
print(f"expired (is_latest=false): {expired['count']}")

# 检查 mapping 中 vector 的类型
mapping = es.indices.get_mapping(index="kb_document_v1")
props = mapping["kb_document_v1"]["mappings"]["properties"]
print(f"vector type: {props.get('vector', {}).get('type', 'NOT FOUND')}")
print(f"keywords type: {props.get('keywords', 'NOT FOUND')}")
