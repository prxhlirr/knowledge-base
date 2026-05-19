"""
从 HuggingFace 下载 Qwen2.5-0.5B-Instruct 到本地目录。
用法：.\venv\Scripts\python.exe download_qwen.py
若网络较慢，可设置镜像：$env:HF_ENDPOINT="https://hf-mirror.com"
"""

import os
from pathlib import Path

# ====== 配置区（按需修改）======
MODEL_ID = "Qwen/Qwen2.5-0.5B-Instruct"
LOCAL_DIR = Path(r"D:\ollamaAI\Qwen2.5-0.5B-Instruct")
HF_TOKEN = None  # 公开模型无需 Token，可留空

# 国内推荐使用镜像（注释掉则走官方 HF）
# os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
# ==============================

def main():
    try:
        from huggingface_hub import snapshot_download
    except ImportError:
        print("正在安装 huggingface_hub ...")
        import subprocess, sys
        subprocess.check_call([sys.executable, "-m", "pip", "install", "-q", "huggingface_hub"])
        from huggingface_hub import snapshot_download

    LOCAL_DIR.mkdir(parents=True, exist_ok=True)
    print(f"📥 开始下载 {MODEL_ID} → {LOCAL_DIR}")
    print("   (首次下载约 1~2GB，请耐心等待...)\n")

    snapshot_download(
        repo_id=MODEL_ID,
        local_dir=str(LOCAL_DIR),
        local_dir_use_symlinks=False,   # Windows 不使用符号链接，直接复制文件
        token=HF_TOKEN,
        ignore_patterns=["*.msgpack", "*.h5", "flax_model*", "tf_model*"],  # 只下载 PyTorch 格式
    )

    print(f"\n✅ 下载完成！模型已保存至：{LOCAL_DIR}")
    print("   目录内容：")
    for f in sorted(LOCAL_DIR.iterdir()):
        size = f.stat().st_size / (1024**2)
        print(f"   {f.name:<40} {size:>8.1f} MB")

if __name__ == "__main__":
    main()
