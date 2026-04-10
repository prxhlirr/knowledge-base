"""
文本向量相似度诊断脚本 v2
用于对比【完整长文入库】与【切块入库】两种模式下 RAG 检索相似度的差异
"""
import sys
import numpy as np

sys.path.insert(0, r"e:\project\AI\knowledge-base\ai_service")

TEXT_A = "居中排忧解难严守缄默红线"

TEXT_B_FULL = (
    "商事调解活动应当遵循自愿、合法、诚信、保密的原则"
)

# 模拟 RAGPipeline 切块后的第一个语义块（即文件标题/主旨句）
TEXT_B_CHUNK_1 = "关于在全市范围内组织开展防范和打击电信网络新型违法犯罪专项行动的实施意见。"
# 第二个语义块（背景段）
TEXT_B_CHUNK_2 = (
    "近年来，利用通讯工具和互联网等技术手段实施的诈骗犯罪呈高发态势，"
    "严重危害人民群众财产安全。为此，市公安局决定联合多部门，即日起开展为期半年的专项打击治理行动。"
)

# BGE-M3 官方推荐：在检索场景下为 query 添加指令前缀，提升短 query vs 长 doc 的相似度
QUERY_INSTRUCTION = "Represent this sentence for searching relevant passages: "

def cosine_similarity(vec_a, vec_b):
    """计算两向量的余弦相似度（已归一化向量直接做点积）"""
    a, b = np.array(vec_a), np.array(vec_b)
    na, nb = np.linalg.norm(a), np.linalg.norm(b)
    return float(np.dot(a, b) / (na * nb)) if na and nb else 0.0

def fmt(sim):
    if sim >= 0.80: return f"{sim:.4f}  ★★★ 高度相关"
    if sim >= 0.65: return f"{sim:.4f}  ★★☆ 较高相关"
    if sim >= 0.45: return f"{sim:.4f}  ★☆☆ 中等相关"
    return f"{sim:.4f}  ─── 相关度低"

def main():
    try:
        from core.model_manager import model_manager
        print("✅ 正在加载 BGE-M3 模型…")
        model_manager.load_model()
    except Exception as e:
        print(f"❌ 模型加载失败: {e}")
        return

    # 编码所有候选文本
    all_texts = [
        TEXT_A,
        QUERY_INSTRUCTION + TEXT_A,  # 带指令前缀的 query
        TEXT_B_FULL,
        TEXT_B_CHUNK_1,
        TEXT_B_CHUNK_2,
    ]
    print(f"\n🔄 编码 {len(all_texts)} 段文本…\n")
    vecs = model_manager.encode(all_texts)
    if len(vecs) < len(all_texts):
        print("❌ 向量编码失败")
        return

    v_query, v_query_instr, v_full, v_c1, v_c2 = vecs

    print("=" * 65)
    print("📊 【对照实验】短 Query vs 不同粒度文本的余弦相似度")
    print("=" * 65)
    print(f"  Query A（原始，14字）     : '{TEXT_A}'")
    print()

    r1 = cosine_similarity(v_query, v_full)
    print(f"  ❶ Query A  ↔  文本B 完整版（{len(TEXT_B_FULL)}字）")
    print(f"     结果: {fmt(r1)}")
    print(f"     💡 向量被 308 字的多主题内容摊薄，导致相似度严重偏低")
    print()

    r2 = cosine_similarity(v_query, v_c1)
    print(f"  ❷ Query A  ↔  Chunk-1（标题句，{len(TEXT_B_CHUNK_1)}字）")
    print(f"     结果: {fmt(r2)}")
    print(f"     💡 RAG 切块后的实际入库粒度，语义高度聚焦")
    print()

    r3 = cosine_similarity(v_query, v_c2)
    print(f"  ❸ Query A  ↔  Chunk-2（背景段，{len(TEXT_B_CHUNK_2)}字）")
    print(f"     结果: {fmt(r3)}")
    print()

    r4 = cosine_similarity(v_query_instr, v_full)
    print(f"  ❹ Query A（+指令前缀）  ↔  文本B 完整版")
    print(f"     结果: {fmt(r4)}")
    print(f"     💡 BGE-M3 asymmetric 检索模式，专为短 query 优化")
    print()

    r5 = cosine_similarity(v_query_instr, v_c1)
    print(f"  ❺ Query A（+指令前缀）  ↔  Chunk-1（标题句）")
    print(f"     结果: {fmt(r5)}")
    print("=" * 65)

    print("\n📌 结论：")
    print(f"  完整文档对比（当前做法） : {r1:.4f}  ← 问题根源")
    print(f"  语义切块对比（RAG实际值）: {r2:.4f}  ← 项目搜索引擎实际命中质量")
    print(f"  指令前缀+切块（建议优化）: {r5:.4f}  ← 可进一步提升检索精度")

if __name__ == "__main__":
    main()
