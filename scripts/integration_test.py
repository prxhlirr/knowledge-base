import requests
import json
import time
import sys

# 服务配置
AI_SERVICE_URL = "http://localhost:8000"
JAVA_SERVICE_URL = "http://localhost:8080"

def print_result(step: str, success: bool, details: str = ""):
    status = "✅ 成功" if success else "❌ 失败"
    print(f"[{status}] {step}")
    if details:
        print(f"   => {details}\n")

def test_ai_health():
    print("--- 1. 测试 Python AI 服务健康状态 ---")
    try:
        resp = requests.get(f"{AI_SERVICE_URL}/api/ai/health", timeout=5)
        resp_json = resp.json()
        if resp.status_code == 200 and resp_json.get("code") == 200:
            print_result("AI 服务状态接口连通", True, json.dumps(resp_json, ensure_ascii=False))
            return True
        else:
            print_result("AI 服务状态异常", False, json.dumps(resp_json, ensure_ascii=False))
            return False
    except Exception as e:
        print_result("AI 服务调用异常 (服务可能未启动)", False, str(e))
        return False

def test_ai_vector_query():
    print("--- 2. 测试 BGE-m3 特征提取链路 ---")
    try:
        payload = {"text": "测试生成基于公文的特征向量"}
        start = time.time()
        resp = requests.post(f"{AI_SERVICE_URL}/api/ai/vector/query", json=payload, timeout=120)
        cost = int((time.time() - start) * 1000)
        
        resp_json = resp.json()
        if resp.status_code == 200 and resp_json.get("code") == 200:
            vector_len = len(resp_json["data"]["vector"])
            print_result(
                "特征提取成功", 
                True, 
                f"耗时: {cost}ms, 接口自报耗时: {resp_json['data']['costMs']}ms, 维度: {vector_len} (预期1024)"
            )
            return vector_len == 1024
        else:
            print_result("特征提取返回错误代码", False, json.dumps(resp_json, ensure_ascii=False))
            return False
    except Exception as e:
        print_result("特征提取链路调用异常", False, str(e))
        return False

def test_java_hybrid_search():
    print("--- 3. 测试 Java 业务网关统一检索接口 (混合搜索) ---")
    try:
        # 组装复杂的边界查询参数
        payload = {
            "queryText": "关于进一步深化科技体制改革的十四五专项指导意见",
            "pageSize": 5,
            "filters": {
                "deptId": 101,
                "fileType": "pdf"
            }
        }
        start = time.time()
        resp = requests.post(f"{JAVA_SERVICE_URL}/api/v1/search", json=payload, timeout=15)
        cost = int((time.time() - start) * 1000)
        
        if resp.status_code == 200:
            resp_json = resp.json()
            if resp_json.get("code") == 200:
                hits = resp_json.get("data", {}).get("list", [])
                total = resp_json.get("data", {}).get("total", 0)
                print_result("混合重排检索端点连通成功", True, f"用时: {cost}ms, 命中条数: {total}")
                for i, hit in enumerate(hits):
                    print(f"   [Hit {i+1}] Score: {hit.get('score')} | Doc ID: {hit.get('doc_id')}")
                return True
            else:
                print_result("Java 接口内部抛错", False, json.dumps(resp_json, ensure_ascii=False))
                return False
        else:
            print_result(f"Java 接口 HTTP {resp.status_code}", False, resp.text)
            return False
    except Exception as e:
        print_result("Java 服务调用异常 (检查服务是否已启动并在监听8080)", False, str(e))
        return False

if __name__ == "__main__":
    print("=================== 知识库 AI 中台核心链路自动化验证 ===================")
    c1 = test_ai_health()
    c2 = test_ai_vector_query()
    c3 = test_java_hybrid_search()
    
    print("=================== 验证总结 ===================")
    if c1 and c2 and c3:
        print("🎉 测试通过：双端 AI 通道与网关路由皆已调通！")
        sys.exit(0)
    else:
        print("⚠️ 测试未完全通过，请检查上方标红或失败处日志，优先确认对应微服务是否挂起。")
        sys.exit(1)
