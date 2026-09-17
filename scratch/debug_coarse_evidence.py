import json
from elasticsearch import Elasticsearch

def debug_query():
    es = Elasticsearch("http://localhost:9200")
    
    # 模拟 buildMatchingCoarseRequestItem 里面的查询 DSL
    # 查询文档 test_report.docx 在 kb_document* 里的 coarse 分片，并且包含 "苹果"
    
    doc_id = "test_report.docx"
    terms = ["苹果"]
    
    # 构建 filter
    filters = []
    # 1. 文件名过滤 (isFileName = True)
    filters.append({
        "bool": {
            "should": [
                {"term": {"metadata.source": doc_id}},
                {"match_phrase": {"metadata.source": doc_id}}
            ],
            "minimum_should_match": 1
        }
    })
    
    # 2. is_latest 过滤
    filters.append({
        "bool": {
            "should": [
                {"term": {"metadata.is_latest": True}},
                {"bool": {"must_not": {"exists": {"field": "metadata.is_latest"}}}}
            ],
            "minimum_should_match": 1
        }
    })
    
    # 3. chunk_granularity 过滤
    filters.append({
        "bool": {
            "should": [
                {"term": {"chunk_granularity": "coarse"}},
                {"bool": {"must_not": {"exists": {"field": "chunk_granularity"}}}}
            ],
            "minimum_should_match": 1
        }
    })
    
    # 4. must 包含至少一个 term ("苹果")
    must_queries = []
    for term in terms:
        must_queries.append({
            "bool": {
                "should": [
                    {"match": {"content": {"query": term, "analyzer": "ik_max_word"}}},
                    {"match_phrase": {"content": {"query": term, "slop": 0, "boost": 8.0}}},
                    {"match": {"display_content": {"query": term, "analyzer": "ik_max_word", "boost": 2.0}}}
                ],
                "minimum_should_match": 1
            }
        })
        
    query = {
        "bool": {
            "filter": filters,
            "must": must_queries
        }
    }
    
    print("Sending Query DSL:")
    print(json.dumps(query, indent=2, ensure_ascii=False))
    
    res = es.search(
        index="kb_document*",
        query=query,
        size=50,
        _source=["content", "display_content", "chunk_granularity", "metadata.source", "metadata.chunk_id", "metadata.is_latest"]
    )
    
    print("\nSearch results:")
    print(f"Total hits: {res['hits']['total']['value']}")
    for hit in res['hits']['hits']:
        print(f"ID: {hit['_id']}")
        print(f"Source: {json.dumps(hit['_source'], indent=2, ensure_ascii=False)}")
        print("-" * 50)

if __name__ == "__main__":
    debug_query()
