# syntax=docker/dockerfile:1
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
# 1. 替换为清华大学镜像源（Tsinghua），解决阿里云在某些环境下 trixie-security 不稳定的问题
# 2. 设置 Acquire::Retries=3 增加下载重试次数，解决网络波动导致的 exit 100
# 3. 合并安装步骤，使用 libreoffice-nogui (无头版) 并添加 --fix-missing
RUN sed -i 's/deb.debian.org/mirrors.tuna.tsinghua.edu.cn/g' /etc/apt/sources.list.d/debian.sources \
    && sed -i 's/security.debian.org/mirrors.tuna.tsinghua.edu.cn/g' /etc/apt/sources.list.d/debian.sources || true \
    && echo 'Acquire::Retries "3";' > /etc/apt/apt.conf.d/80-retries \
    && apt-get update && apt-get install -y --no-install-recommends --fix-missing \
    libgomp1 \
    libmagic1 \
    antiword \
    libgl1 \
    libglib2.0-0 \
    libreoffice-nogui \
    && rm -rf /var/lib/apt/lists/*

# ── 创建非 root 用户 ─────────────────────────────────────────────────────────
RUN useradd -m -u 1000 appuser

WORKDIR /app

# ── 全离线安装 Python 依赖 ──────────────────────────────────────────────────────
# 优化策略（BuildKit --mount=type=bind）：
#   ai-lib/ 目录在构建期通过 bind mount 临时挂载，pip 安装完成后自动卸载，
#   不写入任何镜像层，消除原 COPY ai-lib/ 带来的 ~2GB 冗余层。
#
# 安装内容：
#   [1/4] 安装通用 .whl，但跳过 cuDNN 与 Paddle，由后续步骤确定性安装
#   [2/4] 安装 cuDNN 9.x，匹配 onnxruntime-gpu 1.19.x 的 CUDA Provider
#   [3/4] 安装源码包（如 jieba）
#   [4/4] 验证关键包；PaddleOCR 仅使用 CPU 版 paddlepaddle，显式跳过 paddlepaddle_gpu
#
# CUDA 运行库由宿主机通过 bind mount 提供，镜像保持轻量（无需 nvidia-cuda 基础镜像）
RUN --mount=type=bind,source=ai-lib,target=/tmp/ai-lib \
    echo "--- [1/4] Install wheels from ai-lib/ (skip cuDNN and Paddle variants) ---" \
    && find /tmp/ai-lib -name "*.whl" \
         ! -name "nvidia_cudnn_cu12-*.whl" \
         ! -name "paddlepaddle_gpu*.whl" \
         ! -name "paddlepaddle-*.whl" \
       | xargs -r -I {} pip install --no-deps "{}" \
    \
    && echo "--- [2/4] Install cuDNN 9 for ONNXRuntime CUDA Provider ---" \
    && CUDNN9_WHL=$(find /tmp/ai-lib -name "nvidia_cudnn_cu12-9*.whl" | sort | tail -1) \
    && if [ -n "$CUDNN9_WHL" ]; then \
         pip install --no-deps "$CUDNN9_WHL"; \
       else \
         echo "[ERROR] nvidia_cudnn_cu12-9*.whl not found in ai-lib; onnxruntime-gpu requires libcudnn.so.9" && exit 1; \
       fi \
    \
    && echo "--- [3/4] Install source tarballs (e.g. jieba) ---" \
    && find /tmp/ai-lib -name "*.tar.gz" | xargs -r -I {} pip install --no-deps "{}" \
    \
    && echo "--- [4/4] Install optional PaddleOCR CPU runtime and verify packages ---" \
    && PADDLE_CPU_WHL=$(find /tmp/ai-lib -name "paddlepaddle-*.whl" ! -name "paddlepaddle_gpu*.whl" | sort | tail -1) \
    && if [ -n "$PADDLE_CPU_WHL" ]; then \
         pip install --no-deps "$PADDLE_CPU_WHL" \
         && PADDLE_PROTOBUF_WHL=$(find /tmp/ai-lib -name "protobuf-3.20*.whl" | sort | tail -1) \
         && PADDLE_OPT_EINSUM_WHL=$(find /tmp/ai-lib -name "opt_einsum-3.3*.whl" | sort | tail -1) \
         && if [ -n "$PADDLE_PROTOBUF_WHL" ]; then pip install --no-deps "$PADDLE_PROTOBUF_WHL"; fi \
         && if [ -n "$PADDLE_OPT_EINSUM_WHL" ]; then pip install --no-deps "$PADDLE_OPT_EINSUM_WHL"; fi \
         && python -c "import paddle; print('      paddle CPU OK:', paddle.__version__, 'cuda=', paddle.device.is_compiled_with_cuda())"; \
       else \
         echo "[WARN] CPU paddlepaddle wheel not found; PaddleOCR will be unavailable"; \
       fi \
    \
    && python -c "import cv2; print('      cv2 OK:', cv2.__version__)" \
    && pip show onnxruntime-gpu \
    && python -c "import os; p='/usr/local/lib/python3.10/site-packages/nvidia/cudnn/lib/libcudnn.so.9'; assert os.path.exists(p), p; print('      cuDNN9 OK:', p)" \
    && echo 'import os, glob' > fix_elf.py \
    && echo 'def fix(path):' >> fix_elf.py \
    && echo '    with open(path, "r+b") as f:' >> fix_elf.py \
    && echo '        f.seek(32); ph_off = int.from_bytes(f.read(8), "little")' >> fix_elf.py \
    && echo '        f.seek(54); esz = int.from_bytes(f.read(2), "little")' >> fix_elf.py \
    && echo '        num = int.from_bytes(f.read(2), "little")' >> fix_elf.py \
    && echo '        for i in range(num):' >> fix_elf.py \
    && echo '            f.seek(ph_off + i * esz)' >> fix_elf.py \
    && echo '            if int.from_bytes(f.read(4), "little") == 0x6474e551:' >> fix_elf.py \
    && echo '                pos = f.tell(); flg = int.from_bytes(f.read(4), "little")' >> fix_elf.py \
    && echo '                f.seek(pos); f.write((flg & ~1).to_bytes(4, "little"))' >> fix_elf.py \
    && echo 'for f in glob.glob("/usr/local/lib/python3.10/site-packages/onnxruntime/capi/*.so"): fix(f)' >> fix_elf.py \
    && python3 fix_elf.py && rm fix_elf.py \
    && echo "--- [Offline Fix] onnxruntime-gpu ELF flags patched successfully ---"


# ── 复制应用代码 ─────────────────────────────────────────────────────────────
# 不复制 .env（由外部 volume 挂载），不复制 models/（通过环境变量映射宿主机路径）
# [Cache Busting] 精准失效 COPY core/ 这一层缓存而不影响前面耗时的 apt/pip 层。
# 根因：Docker BuildKit on Windows 有时无法正确检测 core/ 目录文件变更，
#       导致 COPY core/ 错误命中缓存，新代码不进镜像。
# 修复：build-image.bat 传入 --build-arg CACHEBUST=%BUILD_TIME%，
#       ARG 值变化将导致后续所有层全部失效，强制 COPY 执行。
ARG CACHEBUST=1
COPY main.py task_worker.py task_worker_qa.py ./
COPY core/ ./core/
COPY scripts/ ./scripts/

# ── [cuDNN9 路径修复] 将 pip 安装的 cuDNN9 暴露到常见 CUDA lib64 路径 ─────────
# onnxruntime-gpu 1.19.x 的 CUDA Provider 需要 libcudnn.so.9。
# PaddleOCR 仅走 CPU，因此不再创建 cuDNN8 链接，也不安装 paddlepaddle_gpu。
RUN SITE=/usr/local/lib/python3.10/site-packages/nvidia/cudnn/lib \
    && if [ -f "$SITE/libcudnn.so.9" ]; then \
         mkdir -p /usr/local/cuda/lib64 \
         && ln -sf "$SITE/libcudnn.so.9"     /usr/local/cuda/lib64/libcudnn.so.9 \
         && ln -sf "$SITE/libcudnn.so.9"     /usr/local/cuda/lib64/libcudnn.so   \
         && ln -sf "$SITE/libcudnn_ops.so.9" /usr/local/cuda/lib64/libcudnn_ops.so.9 \
         && ln -sf "$SITE/libcudnn_cnn.so.9" /usr/local/cuda/lib64/libcudnn_cnn.so.9 \
         && ln -sf "$SITE/libcudnn_adv.so.9" /usr/local/cuda/lib64/libcudnn_adv.so.9 \
         && echo "[Dockerfile] cuDNN9 symlinks created -> ONNXRuntime CUDA available"; \
       else \
         echo "[ERROR] nvidia-cudnn-cu12 v9 not found; missing libcudnn.so.9" && exit 1; \
       fi

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
