"""
从 HuggingFace 下载真实的 BGE-Reranker-v2-m3 ONNX 模型
用法: $env:HF_ENDPOINT = "https://hf-mirror.com"; .\\venv\\Scripts\\python.exe .\\download_reranker.py
"""

import os
from pathlib import Path

MODEL_ID = "BAAI/bge-reranker-v2-m3"

# 目标目录：替换现有的假 reranker
LOCAL_DIR = Path(r"E:\project\AI\knowledge-base\models\onnx_native\bge-reranker-v2-m3")

os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

def main():
    try:
        from huggingface_hub import snapshot_download
    except ImportError:
        import subprocess, sys
        subprocess.check_call([sys.executable, "-m", "pip", "install", "-q", "huggingface_hub"])
        from huggingface_hub import snapshot_download

    LOCAL_DIR.mkdir(parents=True, exist_ok=True)
    print(f"📥 开始下载 {MODEL_ID} → {LOCAL_DIR}")
    print("   (模型 ~568M 参数，FP32 约 2.2GB；已支持断点续传，网络中断后重新运行即可继续)\n")

    # max_workers=1 + 自动重试：顺序下载更稳定，避免多线程断流
    snapshot_download(
        repo_id=MODEL_ID,
        local_dir=str(LOCAL_DIR),
        max_workers=1,
        ignore_patterns=["*.msgpack", "*.h5", "flax_model*", "tf_model*"],
    )

    print(f"\n✅ 下载完成！")
    total = sum(f.stat().st_size for f in LOCAL_DIR.rglob("*") if f.is_file())
    print(f"   总大小: {total/1024**3:.2f} GB")
    print("\n⚠️  使用前需要导出为 ONNX 格式，运行:")
    print("   pip install optimum[onnxruntime]")
    print(f"   optimum-cli export onnx --model {LOCAL_DIR} --task text-classification {LOCAL_DIR}/onnx")

if __name__ == "__main__":
    main()
