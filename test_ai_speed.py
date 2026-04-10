import time
import requests

def test_speed():
    base_url = "http://127.0.0.1:8001"
    
    # 第一次查询 (可能会触发重新分配显存或编译)
    t0 = time.time()
    try:
        res1 = requests.post(f"{base_url}/api/ai/vector/query", json={"text": "全区域消防"})
        print(f"1st Embedding took: {(time.time() - t0)*1000:.2f} ms | Status: {res1.status_code}")
    except Exception as e:
        print(f"Error: {e}")

    # 第二次查询 (应当完全热身)
    t1 = time.time()
    try:
        res2 = requests.post(f"{base_url}/api/ai/vector/query", json={"text": "全区域消防"})
        print(f"2nd Embedding took: {(time.time() - t1)*1000:.2f} ms")
    except Exception as e:
        print(f"Error: {e}")

    # 测试 Rerank
    t2 = time.time()
    try:
        res3 = requests.post(f"{base_url}/api/ai/rerank", json={
            "query": "全区域消防",
            "documents": ["文档内容一测试", "文档内容二测试，更多消防信息"]
        })
        print(f"1st Rerank took: {(time.time() - t2)*1000:.2f} ms | Status: {res3.status_code}")
    except Exception as e:
        print(f"Error: {e}")

    t3 = time.time()
    try:
        res4 = requests.post(f"{base_url}/api/ai/rerank", json={
            "query": "全区域消防",
            "documents": ["文档内容一测试", "文档内容二测试，更多消防信息"]
        })
        print(f"2nd Rerank took: {(time.time() - t3)*1000:.2f} ms")
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    test_speed()
