import requests
import time

def test_api():
    print("=== 开始测试 AI 服务接口延迟 ===")
    
    # 测试向量模型
    print("1. 发起 Vector 提取请求...")
    t0 = time.time()
    res = requests.post("http://localhost:8000/api/ai/vector/query", json={"text": "数字政府"}, timeout=60)
    t1 = time.time()
    print(f"Vector 耗时: {int((t1-t0)*1000)} ms | 状态码: {res.status_code}")

    # 测试重排模型
    print("2. 发起 Rerank 重排请求 (10篇截断长文)...")
    docs = ["这是一篇模拟出来的非常非常长的关于数字政府的测试文档内容，" * 50] * 10
    t2 = time.time()
    res2 = requests.post("http://localhost:8000/api/ai/rerank", json={
        "query": "数字政府",
        "documents": docs
    }, timeout=60)
    t3 = time.time()
    print(f"Rerank 耗时: {int((t3-t2)*1000)} ms | 状态码: {res2.status_code}")
    
    print("=== 再测一次 (观察缓存与预热后) ===")
    t4 = time.time()
    requests.post("http://localhost:8000/api/ai/vector/query", json={"text": "数据要素"}, timeout=60)
    t5 = time.time()
    print(f"第二次 Vector 耗时: {int((t5-t4)*1000)} ms")

    t6 = time.time()
    requests.post("http://localhost:8000/api/ai/rerank", json={
        "query": "数据要素",
        "documents": docs
    }, timeout=60)
    t7 = time.time()
    print(f"第二次 Rerank 耗时: {int((t7-t6)*1000)} ms")

if __name__ == "__main__":
    test_api()
