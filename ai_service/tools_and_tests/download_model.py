import os
import argparse
from huggingface_hub import snapshot_download

def download_model(model_name, local_dir):
    """
    业务功能：通用模型下载器，支持 Embedding 和 Reranker 模型
    流程说明：
    1. 配置国内镜像源加速。
    2. 使用 snapshot_download 下载全量模型快照。
    3. 自动忽略不需要的巨大中间权重（如 SafeTensors 或 Pytorch Bin 冗余项），节省空间。
    """
    print(f"\n🚀 正在从 HuggingFace 镜像源下载模型: {model_name}")
    print(f"📂 目标路径: {local_dir}")
    
    # 强制使用国内镜像
    os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
    
    if not os.path.exists(local_dir):
        os.makedirs(local_dir)

    try:
        snapshot_download(
            repo_id=model_name,
            local_dir=local_dir,
            local_dir_use_symlinks=False,
            resume_download=True,
            ignore_patterns=["*.msgpack", "*.h5", "*.ot"] # 忽略其他框架格式
        )
        print(f"\n✅ 模型 {model_name} 下载并保存成功！")
    except Exception as e:
        print(f"\n❌ 下载失败: {e}")
        print("提示: 建议检查网络连接，或尝试在命令行手动执行: ")
        print(f"set HF_ENDPOINT=https://hf-mirror.com && huggingface-cli download {model_name} --local-dir {local_dir}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Model Downloader")
    parser.add_argument("--model", type=str, default="BAAI/bge-reranker-v2-m3", help="HuggingFace model ID")
    parser.add_argument("--dir", type=str, default="models/bge-reranker-v2-m3", help="Local directory")
    
    args = parser.parse_args()
    
    # 获取绝对路径
    base_path = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    target_dir = os.path.join(base_path, args.dir)
    
    download_model(args.model, target_dir)
