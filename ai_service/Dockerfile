# ── AI Service（FastAPI + ONNX Runtime GPU）────────────────────────────────────
# 基础镜像: python:3.10-slim
# GPU 支持说明:
#   当前方案: python:3.10-slim + 宿主机 CUDA v12.8 库 bind mount 到容器
#   由于无法拉取 nvidia/cuda 镜像（网络限制），改用宿主机 CUDA 库共享的轻量方案:
#   1. docker-compose.yml volumes: bind mount /C/Program Files/NVIDIA GPU.../CUDA/v12.8/bin→容器
#   2. docker-compose.yml environment: LD_LIBRARY_PATH 指向挂载路径
#   3. ai-lib/ 中已备 onnxruntime_gpu-1.20.1-cp310-cp310-linux_x86_64.whl
#   详见部署文档: docs/gpu-deployment.md
FROM python:3.10-slim

LABEL maintainer="boyang" \
      app="knowledge-base-ai-service" \
      version="1.0.0"

# ── 系统依赖 ─────────────────────────────────────────────────────────────────
# 替换为阿里云镜像源，解决国内环境由于 deb.debian.org 超时导致的 docker build 失败
RUN sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list.d/debian.sources \
    && sed -i 's/security.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list.d/debian.sources || true

# libgomp1: ONNX Runtime CPU 多线程推理必须；libmagic1: python-magic 文件类型检测
# antiword: 政务 .doc OLE 格式解析，antiword -m UTF-8.txt 输出固定编码
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgomp1 \
    libmagic1 \
    antiword \
    libreoffice-writer \
    && rm -rf /var/lib/apt/lists/*

# ── 创建非 root 用户 ─────────────────────────────────────────────────────────
RUN useradd -m -u 1000 appuser

WORKDIR /app

# ── 全离线安装 Python 依赖 ──────────────────────────────────────────────────────
# 策略：ai-lib/ 包含所有运行时依赖的 Linux manylinux wheel，
#   涵盖：onnxruntime-gpu、nvidia-* CUDA 库、markitdown 扩展、striprtf 等。
# 构建时完全无需联网。
#
# 关键处理：先用 find+rm 删除 CPU 版 onnxruntime wheel（文件名 onnxruntime-*.whl），
#   确保最终加载的是 onnxruntime_gpu-*.whl（GPU 版），
#   防止二者同名 Python 包互相覆盖导致 CUDA Provider 静默降级。
COPY ai-lib/ /tmp/ai-lib/
COPY requirements.txt /tmp/requirements.txt
# ai-lib/ 中仅含 onnxruntime_gpu wheel（无 CPU 版、无 nvidia-* CUDA 库 wheel）
# CUDA 运行库由宿主机通过 bind mount 提供，镜像保持轻量
RUN echo "--- [1/3] Install all wheels from ai-lib (--no-deps: CUDA libs from host bind mount) ---" \
    && for f in /tmp/ai-lib/*.whl; do \
         [ -f "$f" ] || continue; \
         pip install --no-deps "$f" || echo "WARNING: Failed $f"; \
       done \
    && echo "--- [2/3] Install source tarballs from ai-lib (e.g. jieba) ---" \
    && for f in /tmp/ai-lib/*.tar.gz; do \
         [ -f "$f" ] || continue; \
         pip install --no-deps "$f" || echo "WARNING: Failed $f"; \
       done \
    && echo "--- [3/3] Verify onnxruntime-gpu installed ---" \
    && pip show onnxruntime-gpu \
    && rm -rf /tmp/ai-lib /tmp/requirements.txt


# ── 复制应用代码 ─────────────────────────────────────────────────────────────
# 不复制 .env（由外部 volume 挂载），不复制 models/（通过环境变量映射宿主机路径）
COPY main.py task_worker.py ./
COPY core/ ./core/

# ── 创建挂载点 ───────────────────────────────────────────────────────────────
# /app/config  ← 宿主机 ai_service/config/.env 挂载至此，可手动编辑
# /app/models  ← 宿主机已有的 BGE-M3 / Reranker ONNX 模型目录挂载至此
RUN mkdir -p /app/config /app/models \
    && chown -R appuser:appuser /app

USER appuser

EXPOSE 8001

HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8001/api/ai/health')" || exit 1

# ── 启动命令 ─────────────────────────────────────────────────────────────────
# dotenv_path 指向挂载的配置文件；MODEL_BASE_PATH 指向挂载的模型目录
# main.py 启动时会 load_dotenv("/app/config/.env")，无需在 CMD 里显式注入
CMD ["python", "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001", "--workers", "1"]
