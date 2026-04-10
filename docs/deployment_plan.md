# 知识库智能搜索系统 - AI 服务离线 Docker 部署方案

基于现有项目架构（Java 后端 + Python AI 服务 + Vue 前端），以下是针对 **AI 服务 (`ai_service`)** 进行完全离线化 Docker 部署的标准化方案。

本方案的核心目标是：**模型与代码一体化封包**、**网络绝对隔离运行**、**核心参数通过 Docker-Compose 外部注入**。

---

## 1. AI 服务工程改造准备 (离线化预处理)

在进行 Docker 镜像构建前，由于目标环境无公网，所有通过网络下载的依赖和模型必须在打包阶段“固化”在本地。

### 1.1 模型权重本地化
AI 服务依赖于本地向量与重排模型（如 BGE/ONNX 等）。请确保模型文件已提前下载好，并统一放置在代码目录内的某个位置，例如建立 `ai_service/models/` 文件夹将权重文件放入其中。

### 1.2 参数环境变量抽取 (12 Factor 改造)
将 [main.py](file:///e:/project/AI/knowledge-base/ai_service/main.py)、[rag_pipeline.py](file:///e:/project/AI/knowledge-base/scripts/rag_pipeline.py)、[model_manager.py](file:///e:/project/AI/knowledge-base/ai_service/core/model_manager.py) 中写死的配置改为从环境变量读取，以便 `docker-compose` 注入：

```python
# 例如在 rag_pipeline.py 中改造 Elasticsearch 连接
import os

ES_HOST = os.getenv("ES_HOST", "http://localhost:9200")
es_client = Elasticsearch([ES_HOST])

# 模型路径配置改造
EMBEDDING_MODEL_PATH = os.getenv("EMBEDDING_MODEL_PATH", "./models/bge-large-zh-v1.5")
RERANK_MODEL_PATH = os.getenv("RERANK_MODEL_PATH", "./models/bge-reranker-large")
```

---

## 2. 编写 Dockerfile

在 `ai_service/` 根目录下创建 `Dockerfile`：

```dockerfile
# 1. 使用官方轻量级 Python 作为基础镜像 (若需 GPU 则替换为 nvidia/cuda:11.8.0-cudnn8-runtime-ubuntu22.04)
FROM python:3.10-slim

# 2. 设置工作目录
WORKDIR /app

# 3. 复制依赖清单 (建议提前锁定精确版本生成 requirements.txt)
COPY requirements.txt .

# 4. 安装 Python 依赖 
# (注意：在打包机上由于有外网可以正常 pip install，离线部署时这些依赖已经被封装在镜像内了)
RUN pip install --no-cache-dir -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple

# 5. 复制工程所有代码及本机的模型权重文件
COPY . .

# 6. 暴露 AI 服务的 FastAPI 端口
EXPOSE 8000

# 7. 启动命令
CMD ["python", "main.py"]
# 或者使用 uvicorn: CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

---

## 3. 编写 docker-compose.yml

创建一个 [docker-compose.yml](file:///e:/project/AI/knowledge-base/docker-compose.yml)，通过 `environment` 显式向外暴露出 AI 服务的所有可配置项。

```yaml
version: '3.8'

services:
  # Python AI 微服务
  ai-service:
    image: knowledge-base-ai:v1.0.0
    container_name: kb-ai-backend
    restart: always
    ports:
      - "8000:8000"
    # 如果宿主机有 N卡并装了 NVIDIA Container Toolkit，取消下方注释开启 GPU 加速
    # deploy:
    #   resources:
    #     reservations:
    #       devices:
    #         - driver: nvidia
    #           count: 1
    #           capabilities: [gpu]
    environment:
      # --- Elasticsearch 配置 ---
      - ES_HOST=http://elasticsearch:9200
      
      # --- 模型挂载/读取路径参数 ---
      - EMBEDDING_MODEL_PATH=/app/models/bge-large-zh-v1.5
      - RERANK_MODEL_PATH=/app/models/bge-reranker-large
      
      # --- 知识搜索调优阈值 ---
      - DEFAULT_VECTOR_TOP_K=10
      - DEVICE=cpu # 若使用GPU可设为 cuda
      
    # 挂载日志和本地额外模型（可选，避免镜像过大也可选择外部 volume 挂载模型）
    volumes:
      - ./logs/ai_service:/app/logs
      # - ./offline_models:/app/models  # (如果模型太大不想打包在镜像里，也可以通过外挂方式)
    
    # 网络设置，如果 ES 和 Java 服务都在同一个 compose 网络下
    networks:
      - kb-network

networks:
  kb-network:
    driver: bridge
```

---

## 4. 离线部署实操规范 (Offline Deployment Workflow)

因为目标服务器完全无外网（没有公网镜像拉取能力），因此需要经过**“有网端封包 -> U盘摆渡 -> 无网端拔包”** 这一经典流程。

### 阶段一：在有公网的开发机上（封包）
1. **构建镜像**：将包含预装依赖、代码甚至轻量级模型的镜像打好。
   ```bash
   cd e:\project\AI\knowledge-base\ai_service\
   docker build -t knowledge-base-ai:v1.0.0 .
   ```
2. **导出镜像实体文件 (.tar)**：
   ```bash
   docker save -o ai-service-v1.tar knowledge-base-ai:v1.0.0
   ```
3. **整理交付物**：将以下三个交付件打包放进 U盘或安全光盘进场：
   - `ai-service-v1.tar` (Docker 镜像压缩包)
   - [docker-compose.yml](file:///e:/project/AI/knowledge-base/docker-compose.yml) (环境编排脚本)
   - `offline_models/` 文件夹 (如果您的 ONNX 或 PyTorch 模型太大没有装进镜像，作为挂载卷一同拷贝)。

### 阶段二：在无公网的生产服务器上（拔包与点火）
生产环境仅需提前安好离线版 `docker` 与 `docker-compose`。
1. **载入离线镜像**：
   ```bash
   docker load -i ai-service-v1.tar
   ```
   *控制台会提示 `Loaded image: knowledge-base-ai:v1.0.0` 即代表导入成功。*

2. **调整环境变量**：
   使用 `vim docker-compose.yml`，将里面的 `ES_HOST`、`DEVICE` 等配置根据当前服务器的真实 IP 集群情况修改好。

3. **一键启动**：
   ```bash
   docker-compose up -d
   ```
4. **验证 AI 节点**：
   ```bash
   docker logs -f kb-ai-backend
   ```
   查看 FastAPI 是否在 `8000` 端口健康挂载并在离线环境下成功加载了分词器和张量权重体系。
