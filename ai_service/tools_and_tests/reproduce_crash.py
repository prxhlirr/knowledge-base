import requests
import json

def reproduce_crash():
    url = "http://127.0.0.1:8001/api/ai/vector/query"
    payload = {"text": "营商环境"}
    
    print(f"🚀 Reproducing crash for query: {payload['text']}")
    try:
        resp = requests.post(url, json=payload)
        print(f"Status Code: {resp.status_code}")
        print(f"Response: {resp.text}")
    except Exception as e:
        print(f"❌ Request Failed: {e}")

if __name__ == "__main__":
    reproduce_crash()
