import urllib.request, json
import sys

# 查询 Python Reranker
def test_reranker():
    req_body = {
        "query": "高质量发展",
        "documents": [
            "测试文档 013 号 - AI知识库专项测试 本文档用于打通Elasticsearch中的原文件更新策略 包含关键词: 营商环境 安全生产 数字化转型",
            "高质量发展是全面建设社会主义现代化国家的首要任务。...要明确主攻方向，要围绕高质量发展，突出关键环节，抓好监督检查。",
            "实行网格化管理，对工作不力、进展缓慢的单位将予以通报批评。 一、提... 要围绕高质量发展，突出薄弱环节，抓好项目实施。"
        ]
    }
    req = urllib.request.Request(
        'http://localhost:8001/api/ai/rerank', 
        data=json.dumps(req_body).encode('utf-8'), 
        headers={'Content-Type': 'application/json'}
    )
    try:
        res = urllib.request.urlopen(req)
        data = json.loads(res.read().decode('utf-8'))
        print("Rerank Scores:", data)
    except Exception as e:
        print("Rerank Error:", e)

# 查询 ES
def test_es():
    query = "高质量发展"
    print("\n--- ES Text Hit ---")
    req_body_text = {
        "query": {
            "bool": {
                "must": [{"multi_match": {"query": query, "fields": ["content^1.5", "metadata.source^1.0", "keywords^5.0"]}}]
            }
        },
        "size": 5
    }
    req_t = urllib.request.Request('http://localhost:9200/kb_document_v1/_search', data=json.dumps(req_body_text).encode('utf-8'), headers={'Content-Type': 'application/json'})
    try:
        data = json.loads(urllib.request.urlopen(req_t).read().decode('utf-8'))
        for i, hit in enumerate(data['hits']['hits']):
             print(f"Text Rank {i+1}: Score={hit['_score']} | {hit['_source']['metadata']['source']}")
    except Exception as e:
        print("ES Text error:", e)
        
    print("\n--- ES Vector Hit ---")
    # need embedding first
    emb_req = urllib.request.Request('http://localhost:8001/api/ai/vector/query', data=json.dumps({"text": query}).encode('utf-8'), headers={'Content-Type': 'application/json'})
    try:
        emb_data = json.loads(urllib.request.urlopen(emb_req).read().decode('utf-8'))
        vector = emb_data['data']['vector']
        req_body_knn = {
            "knn": {
                "field": "vector",
                "query_vector": vector,
                "k": 5, "num_candidates": 50
            },
            "_source": ["metadata.source"]
        }
        req_k = urllib.request.Request('http://localhost:9200/kb_document_v1/_search', data=json.dumps(req_body_knn).encode('utf-8'), headers={'Content-Type': 'application/json'})
        data_k = json.loads(urllib.request.urlopen(req_k).read().decode('utf-8'))
        for i, hit in enumerate(data_k['hits']['hits']):
             print(f"KNN Rank {i+1}: Score={hit['_score']} | {hit['_source']['metadata']['source']}")
    except Exception as e:
        print("ES KNN error:", e)

test_reranker()
test_es()
