import requests
import time
import json

def test_search_perf():
    url = "http://localhost:8080/api/search/hybrid" # 假设 Java 后端接口
    payload = {
        "appCode": "test_app", # 需根据实际可用 appCode 调整
        "query": "测试",
        "topK": 10
    }
    
    # 第一次请求（可能涉及冷启动/缓存）
    start = time.time()
    try:
        resp = requests.post(url, json=payload, timeout=10)
        print(f"First search cost: {time.time() - start:.2f}s")
        if resp.status_code == 200:
            print(f"Results count: {len(resp.json().get('data', []))}")
            # 打印第一条结果看是否为片段
            if resp.json().get('data'):
                print(f"Preview text length: {len(resp.json()['data'][0].get('chunk_text', ''))}")
    except Exception as e:
        print(f"Request failed: {e}")

    # 第二次请求（验证缓存和运行态性能）
    start = time.time()
    try:
        resp = requests.post(url, json=payload, timeout=10)
        print(f"Second search cost: {time.time() - start:.2f}s")
    except Exception as e:
        print(f"Request failed: {e}")

if __name__ == "__main__":
    test_search_perf()
