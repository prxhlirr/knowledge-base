import os
from modelscope.hub.snapshot_download import snapshot_download

# 使用 ModelScope 国内极速通道下载
local_dir = os.path.join(os.getcwd(), "models", "bge-reranker-v2-m3")

print("🚀 开始从 ModelScope 国内节点极速下载 BAAI/bge-reranker-v2-m3...")
try:
    # BAAI/bge-reranker-v2-m3 是模型在魔搭社区的官方 ID
    snapshot_download('BAAI/bge-reranker-v2-m3', local_dir=local_dir)
    print("\n✅ 模型下载并完整校验成功！您可以安全启动 AI 服务了。")
except Exception as e:
    print(f"\n❌ ModelScope 下载失败: {e}")
