import urllib.request
import json
import time

AI_HOST = "http://localhost:8000"

def test_performance():
    print("=== Testing Search Component Performance (Standard Lib) ===")
    
    # 1. Test Embedding
    data = json.dumps({"text": "结婚2年"}).encode('utf-8')
    req = urllib.request.Request(f"{AI_HOST}/api/ai/vector/query", data=data, headers={'Content-Type': 'application/json'})
    
    start = time.time()
    try:
        with urllib.request.urlopen(req) as resp:
            content = json.loads(resp.read().decode())
            cost = (time.time() - start) * 1000
            print(f"Embedding Cost (Total API): {cost:.2f}ms")
            print(f"AI Service reported internal cost: {content['data']['costMs']}ms")
    except Exception as e:
        print(f"Embedding Test Failed: {e}")
    
    # 2. Test Reranking (10 docs, 500 chars limit)
    docs = ["这是一个关于成婚2年的故事。" * 5] * 10
    data = json.dumps({"query": "结婚2年", "documents": docs}).encode('utf-8')
    req = urllib.request.Request(f"{AI_HOST}/api/ai/rerank", data=data, headers={'Content-Type': 'application/json'})
    
    start = time.time()
    try:
        with urllib.request.urlopen(req) as resp:
            content = json.loads(resp.read().decode())
            cost = (time.time() - start) * 1000
            print(f"Reranking Cost (10 docs, Total API): {cost:.2f}ms")
            print(f"AI Service reported internal cost: {content['data']['costMs']}ms")
    except Exception as e:
        print(f"Reranking Test Failed: {e}")

if __name__ == "__main__":
    test_performance()
