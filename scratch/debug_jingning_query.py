import json
from elasticsearch import Elasticsearch

def main():
    es = Elasticsearch("http://localhost:9200")
    
    # 模拟 Java 端在 KeywordCoarseEvidenceStep 中构建的查询条件
    doc_id = "35f59aefe6c292bd349f07aec482af84"
    file_name = "test_legal_statute.docx"
    term = "静宁县"
    
    query = {
        "query": {
            "bool": {
                "filter": [
                    # 1. 文档关联与文件名双轨过滤
                    {
                        "bool": {
                            "should": [
                                {"term": {"metadata.doc_id": doc_id}},
                                {"term": {"metadata.source": file_name}},
                                {"match_phrase": {"metadata.source": file_name}}
                            ],
                            "minimum_should_match": 1
                        }
                    },
                    # 2. 最新版本过滤
                    {
                        "bool": {
                            "should": [
                                {"term": {"metadata.is_latest": True}},
                                {
                                    "bool": {
                                        "must_not": {
                                            "exists": {"field": "metadata.is_latest"}
                                        }
                                    }
                                }
                            ],
                            "minimum_should_match": 1
                        }
                    }
                ],
                # 3. 软性粒度打分
                "should": [
                    {"term": {"chunk_granularity": {"value": "coarse", "boost": 5.0}}},
                    {
                        "bool": {
                            "must_not": {
                                "exists": {"field": "chunk_granularity"}
                            },
                            "boost": 5.0
                        }
                    },
                    {"term": {"chunk_granularity": {"value": "fine", "boost": 1.0}}}
                ],
                # 4. 关键词强匹配 must 子句
                "must": [
                    {
                        "bool": {
                            "should": [
                                {
                                    "match": {
                                        "content": {
                                            "query": term,
                                            "analyzer": "ik_max_word"
                                        }
                                    }
                                },
                                {
                                    "match_phrase": {
                                        "content": {
                                            "query": term,
                                            "slop": 0,
                                            "boost": 8.0
                                        }
                                    }
                                },
                                {
                                    "match": {
                                        "display_content": {
                                            "query": term,
                                            "analyzer": "ik_max_word",
                                            "boost": 2.0
                                        }
                                    }
                                }
                            ],
                            "minimum_should_match": 1
                        }
                    }
                ]
            }
        },
        "size": 50
    }
    
    print("========== 执行与 Java 端完全对齐的 ES Query ==========")
    res = es.search(index="kb_document_official", body=query)
    
    hits = res["hits"]["hits"]
    print(f"ES 返回的 Hits 总数: {res['hits']['total']['value']}")
    
    for i, hit in enumerate(hits):
        src = hit["_source"]
        meta = src.get("metadata", {})
        print(f"\n[Hit {i+1}] ID: {hit['_id']} | Score: {hit['_score']}")
        print(f"  - 粒度 (chunk_granularity): {src.get('chunk_granularity')}")
        print(f"  - 是否最新 (metadata.is_latest): {meta.get('is_latest')}")
        print(f"  - 正文 (content): {repr(src.get('content', '')[:60])}")

if __name__ == "__main__":
    main()
