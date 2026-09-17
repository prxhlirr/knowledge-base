import json
from elasticsearch import Elasticsearch

def main():
    es = Elasticsearch("http://localhost:9200")
    
    # 1. 物理索引里搜索 "焚烧"
    res = es.search(
        index="kb_document*",
        query={"match": {"content": "焚烧"}},
        size=50
    )
    
    print(f"Total physical hits for '焚烧': {res['hits']['total']['value']}")
    for hit in res['hits']['hits']:
        src = hit["_source"]
        meta = src.get("metadata", {})
        print(f"ID: {hit['_id']}")
        print(f"  metadata.source: {meta.get('source')}")
        print(f"  metadata.doc_id: {meta.get('doc_id')}")
        print(f"  chunk_granularity: {src.get('chunk_granularity')}")
        print(f"  content snippet: {src.get('content', '')[:100]}")
        print("-" * 50)

if __name__ == "__main__":
    main()
