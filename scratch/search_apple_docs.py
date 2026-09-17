import json
from elasticsearch import Elasticsearch

def main():
    es = Elasticsearch("http://localhost:9200")
    
    # 1. 搜索 kb_document*
    res = es.search(index="kb_document*", query={"match": {"content": "苹果"}}, size=5)
    docs_document = []
    for hit in res['hits']['hits']:
        docs_document.append({
            "id": hit["_id"],
            "index": hit["_index"],
            "source": hit["_source"]
        })
        
    # 2. 搜索 kb_doc_search*
    res2 = es.search(index="kb_doc_search*", query={"multi_match": {"query": "苹果", "fields": ["title", "doc_terms", "summary"]}}, size=5)
    docs_doc_search = []
    for hit in res2['hits']['hits']:
        docs_doc_search.append({
            "id": hit["_id"],
            "index": hit["_index"],
            "source": hit["_source"]
        })
        
    output = {
        "kb_document": docs_document,
        "kb_doc_search": docs_doc_search
    }
    
    with open("scratch/apple_docs_utf8.json", "w", encoding="utf-8") as f:
        json.dump(output, f, indent=2, ensure_ascii=False)
        
    print("Done writing to scratch/apple_docs_utf8.json")

if __name__ == "__main__":
    main()
