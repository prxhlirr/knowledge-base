import requests
import json

def test_search_scores():
    url = "http://127.0.0.1:8080/api/v1/search"
    headers = {
        "Content-Type": "application/json",
        "X-Search-AppCode": "boyang-kb"
    }
    payload = {
        "queryText": "结婚2年",
        "pageSize": 10
    }
    
    print(f"🚀 Testing Java Search for query: {payload['queryText']}")
    resp = requests.post(url, json=payload, headers=headers)
    
    if resp.status_code == 200:
        data = resp.json()
        results = data['data']['list']
        print(f"Total results: {len(results)}")
        for i, item in enumerate(results):
            print(f"[{i+1}] Title: {item.get('title', 'N/A')}")
            print(f"    Score: {item.get('score')}")
            print(f"    Raw Text: {item.get('chunk_text')[:50]}...")
            print("-" * 30)
    else:
        print(f"❌ Error {resp.status_code}: {resp.text}")

if __name__ == "__main__":
    test_search_scores()
