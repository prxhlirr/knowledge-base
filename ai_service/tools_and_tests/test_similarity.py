import sys
import os
import numpy as np

# 将项目根目录添加到路径
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from core.model_manager import model_manager

def test_similarity():
    # 初始化模型
    model_manager.load_model()
    
    texts = ["结婚2年", "成婚2年", "今天天气不错", "办理结婚证"]
    
    print(f"\n=== Testing Semantic Similarity ===")
    embeddings = []
    for text in texts:
        vec = model_manager.encode(text)[0]
        embeddings.append(np.array(vec))
        print(f"Encoded: '{text}'")

    def cosine_similarity(v1, v2):
        return np.dot(v1, v2) / (np.linalg.norm(v1) * np.linalg.norm(v2))

    # 计算 结婚 vs 成婚
    sim_target = cosine_similarity(embeddings[0], embeddings[1])
    # 计算 结婚 vs 天气 (对照组)
    sim_unrelated = cosine_similarity(embeddings[0], embeddings[2])
    # 计算 结婚 vs 办理结婚证
    sim_related = cosine_similarity(embeddings[0], embeddings[3])

    print(f"\nSimilarity Scores (0.0 to 1.0):")
    print(f"1. '结婚2年' vs '成婚2年': {sim_target:.4f}")
    print(f"2. '结婚2年' vs '今天天气不错': {sim_unrelated:.4f}")
    print(f"3. '结婚2年' vs '办理结婚证': {sim_related:.4f}")

    if sim_target > 0.8:
        print("\n✅ Model semantic understanding is GOOD.")
    else:
        print("\n❌ Model semantic understanding might be WEAK for these synonyms.")

if __name__ == "__main__":
    test_similarity()
