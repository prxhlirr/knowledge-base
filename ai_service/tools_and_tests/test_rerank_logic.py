import requests
import json

def test_rerank():
    url = "http://127.0.0.1:8001/api/ai/rerank"
    payload = {
        "query": "结婚2年",
        "documents": [
            "Cross-Encoder重排技术的赋能下，政务效率预计提升80%。",
            "从一开始我就知道我的归宿，我按着它去选择",
            "法律规定，成婚满两年后，夫妻双方可以申请某项特定的家庭补贴政策。"
        ]
    }
    
    print(f"🚀 Testing Rerank for query: {payload['query']}")
    resp = requests.post(url, json=payload)
    data = resp.json()
    
    if data['code'] == 200:
        scores = data['data']['scores']
        for i, doc in enumerate(payload['documents']):
            print(f"Doc {i+1}: {doc[:30]}...")
            print(f"Score: {scores[i]}")
    else:
        print(f"❌ Error: {data}")

if __name__ == "__main__":
    test_rerank()
