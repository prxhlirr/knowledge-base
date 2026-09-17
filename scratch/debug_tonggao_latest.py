import json
from elasticsearch import Elasticsearch

def main():
    es = Elasticsearch("http://localhost:9200")
    
    # 查询所有 test_tonggao.docx 的分片
    res = es.search(
        index="kb_document_official",
        query={"term": {"metadata.source": "test_tonggao.docx"}},
        size=100
    )
    
    print(f"Total chunks for test_tonggao.docx: {res['hits']['total']['value']}")
    is_latest_values = {}
    for hit in res['hits']['hits']:
        src = hit["_source"]
        meta = src.get("metadata", {})
        is_latest = meta.get("is_latest")
        is_latest_values[hit["_id"]] = {
            "is_latest": is_latest,
            "chunk_granularity": src.get("chunk_granularity")
        }
        
    print(json.dumps(is_latest_values, indent=2))

if __name__ == "__main__":
    main()
