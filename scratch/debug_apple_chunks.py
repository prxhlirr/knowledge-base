import json
from elasticsearch import Elasticsearch

def main():
    es = Elasticsearch("http://localhost:9200")
    
    # 检索 content 中包含“苹果”的分片
    print("========== 检索 content 中包含 '苹果' 的分片 ==========")
    res = es.search(
        index="kb_document_official",
        query={
            "match": {
                "content": "苹果"
            }
        },
        size=10
    )
    
    hits = res["hits"]["hits"]
    print(f"共召回包含 '苹果' 的分片数: {res['hits']['total']['value']}")
    
    for i, hit in enumerate(hits):
        src = hit["_source"]
        meta = src.get("metadata", {})
        print(f"\n[分片 {i+1}] ID: {hit['_id']}")
        print(f"  - 文件名 (metadata.source): {meta.get('source')}")
        print(f"  - 粒度 (chunk_granularity): {src.get('chunk_granularity')}")
        print(f"  - 是否最新 (metadata.is_latest): {meta.get('is_latest')}")
        print(f"  - 正文前 100 字符 (content): {repr(src.get('content', '')[:100])}")
        
if __name__ == "__main__":
    main()
