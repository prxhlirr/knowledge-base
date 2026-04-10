import sys
import os
import time

# 将项目根目录添加到路径
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from core.model_manager import model_manager

def verify():
    print("=== Testing GPU-First Loading ===")
    start_time = time.time()
    
    # 触发加载
    model_manager.load_model()
    
    load_duration = time.time() - start_time
    print(f"\nTotal loading and warmup duration: {load_duration:.2f}s")
    
    # 检查状态
    status = model_manager.get_health_status()
    print(f"Status: {status}")
    
    if status["device"] == "gpu":
        print("✅ Success: Model loaded with GPU acceleration.")
    else:
        print("ℹ️ Note: Model fell back to CPU (expected if GPU is busy or incompatible).")

    # 测试编码性能
    print("\n=== Testing Inference Performance (10 queries) ===")
    queries = ["测试检索性能", "人工智能应用", "向量检索系统"] * 3 + ["最后的测试"]
    
    infer_start = time.time()
    for q in queries:
        model_manager.encode(q)
    infer_duration = time.time() - infer_start
    
    avg_duration = (infer_duration / len(queries)) * 1000
    print(f"Average inference time: {avg_duration:.2f}ms")
    
    if avg_duration < 1000:
        print(f"✅ Success: Performance goal met (< 1s per query).")
    else:
        print(f"❌ Failed: Performance goal NOT met (> 1s per query).")

if __name__ == "__main__":
    verify()
