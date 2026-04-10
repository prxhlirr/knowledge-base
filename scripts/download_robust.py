import os
import shutil
import time
from huggingface_hub import snapshot_download

local_dir = "models/bge-reranker-v2-m3"
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"

while True:
    try:
        print("正在连接 hf-mirror 下载 BAAI/bge-reranker-v2-m3...")
        snapshot_download(
            repo_id="BAAI/bge-reranker-v2-m3",
            local_dir=local_dir,
            local_dir_use_symlinks=False,
            resume_download=True,
            ignore_patterns=["*.msgpack", "*.h5", "*.ot"]
        )
        
        # 严格校验核心权重文件
        if os.path.exists(os.path.join(local_dir, "model.safetensors")) or os.path.exists(os.path.join(local_dir, "pytorch_model.bin")):
            print("✅ 下载成功，核心权重文件 model.safetensors / pytorch_model.bin 已就位！")
            break
        else:
            print("⚠️ 警告：目录存在，但未找到核心权重（可能是上次下载意外中断导致的假完成）。")
            print("正在清理损坏的缓存，这需要一点时间...")
            shutil.rmtree(local_dir, ignore_errors=True)
            time.sleep(2)
            
    except Exception as e:
        print("❌ 下载阶段异常:", e)
        print("将在 5 秒后执行断点续传重试...")
        time.sleep(5)
