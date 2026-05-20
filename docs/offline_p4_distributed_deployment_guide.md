# 离线双 P4 服务器分布式部署指南

本文档面向当前项目的离线部署场景：

- 现有 2 台 P4 服务器。
- 每台服务器 2 张 NVIDIA Tesla P4 8GB GPU。
- 后续可能继续扩充同规格服务器。
- 当前项目中 Embedding、OCR、ColBERT、Rerank、LLM 都由 `ai_service` 或 Ollama 承担。

目标是把在线检索、问答生成、文档入库三类负载隔离，避免某一类慢任务拖垮整个 `/home` 检索和问答链路。

## 一、第一性原理

GPU 只应该服务高密度矩阵计算。Java、前端、Redis、MySQL/PostgreSQL、MinIO、Elasticsearch 本身不需要 GPU。

当前项目中的 GPU 负载可分为四类：

| 负载 | 发生阶段 | 项目入口 | 特征 | 部署原则 |
|---|---|---|---|---|
| Query Embedding | 在线检索 | `/api/ai/vector/*` | 高频、短耗时、强延迟敏感 | 必须独立保障 |
| ColBERT | 在线检索 | `/api/ai/colbert/score` | 与 BGE embedding 模型相关 | 可与在线 Embedding 同卡 |
| Rerank | 在线检索 | `/api/ai/rerank/*` | Cross-Encoder，候选越多越慢 | 与 Embedding 分卡 |
| LLM / Ollama | 问答、HyDE、LLM rerank | `/api/ai/llm/*` | 长耗时、显存占用强、首 token 慢 | 独占 GPU |
| OCR | 文档入库 | `task_worker` / PaddleOCR | 批处理、慢、波动大 | 与在线检索隔离 |
| 入库 Embedding | 文档入库 | `task_worker` 内部调用 BGE | 吞吐型批处理 | 与在线 Query Embedding 隔离 |

核心原则：

1. 在线检索 GPU 和文档入库 GPU 必须隔离。
2. LLM 必须单独隔离。
3. P4 8GB 不建议一张卡混跑 LLM、Embedding、Rerank、OCR。
4. Worker 只能在专用入库节点启动，在线 AI 节点必须 `AI_START_WORKER=false`。

## 二、推荐资源分配

假设：

- 服务器 A：在线检索节点，IP 示例 `10.0.0.11`
- 服务器 B：LLM + 入库节点，IP 示例 `10.0.0.12`
- Java / 前端 / ES / Redis / DB 可以部署在服务器 A 或独立业务服务器，IP 示例 `10.0.0.10`

推荐分配：

| 服务器 | GPU | 服务 | 端口 | 用途 |
|---|---:|---|---:|---|
| A | GPU0 | `ai-embedding` | 8001 | 在线 Query Embedding + ColBERT + HyDE 编码 |
| A | GPU1 | `ai-rerank` | 8002 | 在线 Cross-Encoder Rerank |
| B | GPU0 | `ollama` | 11434 | Qwen / LLM 问答生成 |
| B | GPU1 | `ai-worker` | 8001 | OCR + 文档入库 Embedding + ES 写入 |

调用链：

```text
在线检索：
Frontend -> Java /api/v1/search/home
  -> A:8001 ai-embedding
  -> Elasticsearch
  -> A:8001 ai-embedding/colbert
  -> A:8002 ai-rerank
  -> B:11434 ollama 或 B:8002 ai-llm-proxy

文档入库：
Java 上传文档 -> Redis 队列
  -> B:8001 ai-worker
  -> OCR
  -> 入库 embedding
  -> Elasticsearch
```

## 三、代码配置已支持的能力

当前项目已支持以下环境变量：

Java 侧：

```env
AI_SERVICE_HOST=http://10.0.0.11:8001
AI_EMBEDDING_HOST=http://10.0.0.11:8001
AI_COLBERT_HOST=http://10.0.0.11:8001
AI_RERANK_HOST=http://10.0.0.11:8002
AI_LLM_HOST=http://10.0.0.11:8001
```

Python AI 侧：

```env
AI_CAPABILITIES=embedding,colbert,llm
AI_START_WORKER=false
```

关键说明：

- `AI_CAPABILITIES` 控制当前 AI 实例开放哪些接口。
- `AI_START_WORKER` 控制当前 AI 实例是否消费 Redis 入库任务。
- 在线检索 AI 节点必须设置 `AI_START_WORKER=false`。
- 只有文档入库节点设置 `AI_START_WORKER=true`。

## 四、模型环境变量配置位置

模型相关环境变量有两个推荐配置位置：

1. **优先写在各服务器的 `docker-compose-*.yml` 的 `environment` 中**。这是最直观、最不容易串配置的方式，适合分布式部署。
2. **也可以写在宿主机挂载目录 `./ai_service/config/.env` 中**。`ai_service/main.py` 启动时会优先加载容器内 `/app/config/.env`，也就是 compose 中挂载的 `./ai_service/config/.env`。

实际优先级：

```text
docker-compose.yml environment
  > ./ai_service/config/.env
  > 代码默认值
```

因此离线部署时建议：

- 每台服务器一份自己的 `docker-compose-*.yml`。
- 每台服务器一份自己的 `ai_service/config/.env`。
- 如果某个变量在 compose 里已经写了，就不要在 `.env` 里写冲突值。

### 4.1 LLM / Ollama 模型变量

这些变量配置在**需要调用 LLM 的 AI 服务实例**上，例如服务器 A 的 `ai-embedding`，或后续独立的 `ai-llm-proxy`。

```env
LLM_API_URL=http://10.0.0.12:11434/v1/chat/completions
LLM_MODEL=qwen2.5:7b
QA_LLM_MODEL=qwen2.5:7b
HYDE_LLM_MODEL=qwen2.5:7b
RERANKER_LLM_MODEL=qwen2.5:7b
QA_LLM_MAX_TOKENS=600
LLM_CONNECT_TIMEOUT=10
LLM_READ_TIMEOUT=180
LLM_STREAM_READ_TIMEOUT=300
LLM_SEND_ENABLE_THINKING=false
LLM_SEND_THINK_FALSE=false
```

变量用途：

| 变量 | 用途 | 推荐配置节点 |
|---|---|---|
| `LLM_API_URL` | Ollama OpenAI-compatible 接口地址 | 所有需要调 LLM 的 AI 节点 |
| `LLM_MODEL` | 意图改写、通用 LLM 调用 | `ai-embedding` 或 `ai-llm-proxy` |
| `QA_LLM_MODEL` | `/home` 知识问答生成 | `ai-embedding` 或 `ai-llm-proxy` |
| `HYDE_LLM_MODEL` | HyDE 假设文档生成 | `ai-embedding` |
| `RERANKER_LLM_MODEL` | LLM rerank，可选 | `ai-llm-proxy` |
| `QA_LLM_MAX_TOKENS` | QA 最大输出长度 | `ai-embedding` 或 `ai-llm-proxy` |

注意：

- 模型名必须和 Ollama 中 `ollama list` 看到的名称完全一致。
- 如果现场模型是 `qwen3.5:4b`，则把上述模型变量统一改为 `qwen3.5:4b`。
- P4 8GB 上建议 `OLLAMA_NUM_PARALLEL=1`，避免并发导致显存不足和 read timeout。

### 4.2 Embedding / Rerank ONNX 模型变量

这些变量配置在对应的 Python AI 节点上。

在线 Embedding 节点：

```env
MODEL_BASE_PATH=/app/models/onnx_native
AI_CAPABILITIES=embedding,colbert,llm
AI_START_WORKER=false
AI_MODEL_PRELOAD=true
EMBEDDING_GPU_ID=0
NVIDIA_VISIBLE_DEVICES=0
```

在线 Rerank 节点：

```env
MODEL_BASE_PATH=/app/models/onnx_native
AI_CAPABILITIES=rerank
AI_START_WORKER=false
AI_MODEL_PRELOAD=true
RERANKER_GPU_ID=0
NVIDIA_VISIBLE_DEVICES=1
```

说明：

- `MODEL_BASE_PATH` 是容器内路径，不是宿主机路径。
- 当前 compose 中通常挂载 `./models:/app/models:ro`，所以容器内统一写 `/app/models/onnx_native`。
- 使用 `NVIDIA_VISIBLE_DEVICES=1` 后，容器内看到的这张物理 GPU1 通常会被重映射成 `cuda:0`，所以容器内的 `RERANKER_GPU_ID` 仍建议写 `0`。

### 4.3 OCR / 入库 Worker 模型变量

这些变量只配置在服务器 B 的 `ai-worker` 上。

```env
MODEL_BASE_PATH=/app/models/onnx_native
PADDLE_OCR_MODEL_DIR=/app/models/paddle_ocr
PADDLE_OCR_OFFLINE_STRICT=true
AI_CAPABILITIES=worker,ocr,embedding
AI_START_WORKER=true
AI_MODEL_PRELOAD=false
OCR_USE_GPU=true
OCR_GPU_ID=0
EMBEDDING_GPU_ID=0
NVIDIA_VISIBLE_DEVICES=1
OCR_RENDER_DPI=220
OCR_MIN_RENDER_DPI=150
OCR_MAX_RENDER_PIXELS=6000000
```

注意：

- Worker 节点需要 `embedding` 能力，因为入库时会对文档 chunk 做向量化。
- Worker 节点必须 `AI_START_WORKER=true`。
- 在线检索节点必须 `AI_START_WORKER=false`。
- OCR 模型目录必须完整，否则离线严格模式下会快速失败，避免运行时访问公网下载。

### 4.4 Ollama 服务变量

这些变量配置在服务器 B 的 `ollama` 服务上。

```env
OLLAMA_HOST=0.0.0.0:11434
OLLAMA_NUM_GPU=1
OLLAMA_NUM_PARALLEL=1
OLLAMA_MAX_LOADED_MODELS=1
OLLAMA_GPU_OVERHEAD=512
NVIDIA_VISIBLE_DEVICES=0
```

说明：

- `OLLAMA_HOST=0.0.0.0:11434` 是跨服务器访问的关键。
- 如果只监听 `127.0.0.1`，服务器 A 的 AI 服务无法访问服务器 B 的 Ollama。
- `OLLAMA_NUM_PARALLEL=1` 更适合 P4 8GB，优先保证稳定。

### 4.5 Java 侧模型相关变量

Java 不直接加载模型，只负责选择调用哪个 AI 服务地址。

这些变量配置在 Java 服务的 compose 中：

```env
AI_SERVICE_HOST=http://10.0.0.11:8001
AI_EMBEDDING_HOST=http://10.0.0.11:8001
AI_COLBERT_HOST=http://10.0.0.11:8001
AI_RERANK_HOST=http://10.0.0.11:8002
AI_LLM_HOST=http://10.0.0.11:8001
QA_LLM_MAX_TOKENS=600
QA_LLM_CONNECT_TIMEOUT_MS=5000
QA_LLM_READ_TIMEOUT_MS=60000
QA_STREAM_TIMEOUT_MS=70000
QA_PROMPT_MAX_CHARS=6000
```

说明：

- Java 侧的 `QA_LLM_MAX_TOKENS` 会控制 Java 传给 AI `/api/ai/llm/chat_stream` 的 `max_tokens`。
- Python AI 侧的 `QA_LLM_MODEL` 决定最终调用 Ollama 的模型名。
- `AI_LLM_HOST` 建议先指向 `ai-embedding`，由它代理到 Ollama；后续如单独部署 `ai-llm-proxy`，再改成 proxy 地址。

## 五、服务器 A compose：在线检索节点

在服务器 A 上创建 `docker-compose-ai-online.yml`：

```yaml
version: '3.8'

services:
  ai-embedding:
    image: knowledge-base-ai:1.0.0
    container_name: kb-ai-embedding
    runtime: nvidia
    ports:
      - "8001:8001"
    volumes:
      - ./ai_service/config:/app/config:ro
      - ./models:/app/models:ro
    environment:
      - MODEL_BASE_PATH=/app/models/onnx_native
      - AI_CAPABILITIES=embedding,colbert,llm
      - AI_START_WORKER=false
      - AI_MODEL_PRELOAD=true
      - EMBEDDING_GPU_ID=0
      - NVIDIA_VISIBLE_DEVICES=0
      - REDIS_HOST=10.0.0.10
      - REDIS_PORT=6379
      - REDIS_PASSWORD=<CHANGE_ME_REDIS_PASSWORD>
      - ES_HOST=http://10.0.0.10:9200
      - JAVA_SERVICE_HOST=http://10.0.0.10:8080
      - JAVA_API_BASE=http://10.0.0.10:8080/api/v1
      - LLM_API_URL=http://10.0.0.12:11434/v1/chat/completions
      - LLM_CONNECT_TIMEOUT=10
      - LLM_READ_TIMEOUT=180
      - LLM_STREAM_READ_TIMEOUT=300
      - LLM_SEND_ENABLE_THINKING=false
      - LLM_SEND_THINK_FALSE=false
      - OCR_USE_GPU=false
      - PYTHONIOENCODING=utf-8
      - LANG=C.UTF-8
      - LC_ALL=C.UTF-8
    command: >
      bash -c "python -m uvicorn main:app --host 0.0.0.0 --port 8001 --workers 1"
    restart: always

  ai-rerank:
    image: knowledge-base-ai:1.0.0
    container_name: kb-ai-rerank
    runtime: nvidia
    ports:
      - "8002:8001"
    volumes:
      - ./ai_service/config:/app/config:ro
      - ./models:/app/models:ro
    environment:
      - MODEL_BASE_PATH=/app/models/onnx_native
      - AI_CAPABILITIES=rerank
      - AI_START_WORKER=false
      - AI_MODEL_PRELOAD=true
      - RERANKER_GPU_ID=0
      - NVIDIA_VISIBLE_DEVICES=1
      - OCR_USE_GPU=false
      - PYTHONIOENCODING=utf-8
      - LANG=C.UTF-8
      - LC_ALL=C.UTF-8
    command: >
      bash -c "python -m uvicorn main:app --host 0.0.0.0 --port 8001 --workers 1"
    restart: always
```

说明：

- `ai-embedding` 使用物理 GPU0。
- `ai-rerank` 使用物理 GPU1。
- 容器内 GPU 会被 `NVIDIA_VISIBLE_DEVICES` 重映射，所以容器内仍配置 `EMBEDDING_GPU_ID=0` / `RERANKER_GPU_ID=0`。
- `ai-embedding` 保留 `llm` 能力，是为了兼容当前 `/intent/rewrite_and_hyde`：该接口会调用远端 Ollama 生成 HyDE，再本地做 embedding。

启动：

```bash
docker compose -f docker-compose-ai-online.yml up -d
```

## 六、服务器 B compose：LLM + 入库节点

在服务器 B 上创建 `docker-compose-ai-offline-worker.yml`：

```yaml
version: '3.8'

services:
  ollama:
    image: ollama/ollama:latest
    container_name: ollama_llm
    runtime: nvidia
    ports:
      - "11434:11434"
    volumes:
      - ollamadata:/root/.ollama
    environment:
      - OLLAMA_HOST=0.0.0.0:11434
      - OLLAMA_NUM_GPU=1
      - OLLAMA_NUM_PARALLEL=1
      - OLLAMA_MAX_LOADED_MODELS=1
      - OLLAMA_GPU_OVERHEAD=512
      - NVIDIA_VISIBLE_DEVICES=0
    restart: always

  ai-worker:
    image: knowledge-base-ai:1.0.0
    container_name: kb-ai-worker
    runtime: nvidia
    ports:
      - "8001:8001"
    volumes:
      - ./ai_service/config:/app/config:ro
      - ./models:/app/models:ro
    environment:
      - MODEL_BASE_PATH=/app/models/onnx_native
      - AI_CAPABILITIES=worker,ocr,embedding
      - AI_START_WORKER=true
      - AI_MODEL_PRELOAD=false
      - EMBEDDING_GPU_ID=0
      - OCR_USE_GPU=true
      - OCR_GPU_ID=0
      - NVIDIA_VISIBLE_DEVICES=1
      - REDIS_HOST=10.0.0.10
      - REDIS_PORT=6379
      - REDIS_PASSWORD=<CHANGE_ME_REDIS_PASSWORD>
      - ES_HOST=http://10.0.0.10:9200
      - JAVA_SERVICE_HOST=http://10.0.0.10:8080
      - JAVA_API_BASE=http://10.0.0.10:8080/api/v1
      - PADDLE_OCR_MODEL_DIR=/app/models/paddle_ocr
      - PADDLE_OCR_OFFLINE_STRICT=true
      - OCR_RENDER_DPI=220
      - OCR_MIN_RENDER_DPI=150
      - OCR_MAX_RENDER_PIXELS=6000000
      - PYTHONIOENCODING=utf-8
      - LANG=C.UTF-8
      - LC_ALL=C.UTF-8
    command: >
      bash -c "python -m uvicorn main:app --host 0.0.0.0 --port 8001 --workers 1"
    restart: always

volumes:
  ollamadata:
    driver: local
```

启动：

```bash
docker compose -f docker-compose-ai-offline-worker.yml up -d
```

首次部署 Ollama 后，导入或拉取离线模型，例如：

```bash
docker exec -it ollama_llm ollama list
```

如果模型已经离线拷贝到 `/root/.ollama`，确认 `ollama list` 能看到对应模型名，例如 `qwen2.5:7b` 或现场使用的模型名。

## 七、Java / 前端 / 中间件 compose 修改

当前项目的 `docker-compose-offline.yml` 已经是 All-in-One 风格。分布式部署时，Java 服务里必须配置分角色 AI 地址。

在 `knowledge-base-java.environment` 中设置：

```yaml
environment:
  - AI_SERVICE_HOST=http://10.0.0.11:8001
  - AI_EMBEDDING_HOST=http://10.0.0.11:8001
  - AI_COLBERT_HOST=http://10.0.0.11:8001
  - AI_RERANK_HOST=http://10.0.0.11:8002
  - AI_LLM_HOST=http://10.0.0.11:8001
```

推荐先让 `AI_LLM_HOST` 指向 `ai-embedding`，由 `ai-embedding` 代理调用服务器 B 的 Ollama：

```env
LLM_API_URL=http://10.0.0.12:11434/v1/chat/completions
```

这样 Java 不需要直接理解 Ollama 的 OpenAI-compatible 协议，仍然调用项目内的 `/api/ai/llm/chat_stream`。

如果后续单独部署 `ai-llm-proxy`，可改为：

```yaml
- AI_LLM_HOST=http://10.0.0.12:8002
```

## 八、端口与防火墙

至少开放以下内网端口：

| 来源 | 目标 | 端口 | 用途 |
|---|---|---:|---|
| Java 服务器 | 服务器 A | 8001 | Embedding / ColBERT / LLM proxy |
| Java 服务器 | 服务器 A | 8002 | Rerank |
| 服务器 A | 服务器 B | 11434 | Ollama |
| 服务器 B Worker | Redis | 6379 | 消费入库任务 |
| 服务器 B Worker | ES | 9200 | 写入索引 |
| 服务器 B Worker | Java | 8080 | 回调任务状态 |

不建议把这些端口开放到公网。仅允许内网服务器互访。

## 九、模型和目录要求

所有 AI 节点需要挂载模型目录：

```text
models/
  onnx_native/
    bge-m3/
      model.onnx
      tokenizer.json
      tokenizer_config.json
    bge-reranker-v2-m3/
      onnx/
        model.onnx
      tokenizer.json
  paddle_ocr/
    ch_PP-OCRv4_det_infer/
    ch_PP-OCRv4_rec_infer/
    ch_ppocr_mobile_v2.0_cls_infer/
    ch_ppstructure_mobile_v2.0_SLANet_infer/
    picodet_lcnet_x1_0_fgd_layout_infer/
```

Embedding 节点必须有：

- `models/onnx_native/bge-m3`

Rerank 节点必须有：

- `models/onnx_native/bge-reranker-v2-m3`

Worker 节点必须有：

- `models/onnx_native/bge-m3`
- `models/paddle_ocr`

Ollama 节点必须有：

- `/root/.ollama` 中的 qwen 模型。

## 十、验收命令

服务器 A：

```bash
curl http://10.0.0.11:8001/api/ai/health
curl http://10.0.0.11:8002/api/ai/health
```

期望：

```json
{
  "status": "ok",
  "capabilities": {
    "embedding": {"enabled": true},
    "rerank": {"enabled": false}
  },
  "worker_enabled": false
}
```

`ai-rerank` 期望：

```json
{
  "capabilities": {
    "rerank": {"enabled": true}
  },
  "worker_enabled": false
}
```

服务器 B：

```bash
curl http://10.0.0.12:11434/api/tags
curl http://10.0.0.12:8001/api/ai/health
```

Worker 节点期望：

```json
{
  "capabilities": {
    "worker": {"enabled": true},
    "ocr": {"enabled": true},
    "embedding": {"enabled": true}
  },
  "worker_enabled": true
}
```

GPU 验证：

```bash
docker exec kb-ai-embedding python -c "import onnxruntime; print(onnxruntime.get_available_providers())"
docker exec kb-ai-rerank python -c "import onnxruntime; print(onnxruntime.get_available_providers())"
docker exec ollama_llm ollama ps
nvidia-smi
```

期望 ONNX Runtime 包含：

```text
CUDAExecutionProvider
```

## 十一、检索链路验证

从前端或直接请求 Java：

```bash
curl -N -X POST http://10.0.0.10:8080/api/v1/search/home \
  -H "Content-Type: application/json" \
  -d '{"query":"测试","mode":"hybrid","topK":10}'
```

观察日志：

- `kb-ai-embedding` 应出现 `/vector/dual`、`/colbert/score` 或 `/intent/rewrite_and_hyde`。
- `kb-ai-rerank` 应出现 `/rerank`。
- `ollama_llm` 应出现模型推理。
- `kb-ai-worker` 不应因在线检索产生大量日志。

如果 `/home` 出现 500，优先检查：

1. Java 的 `AI_EMBEDDING_HOST` / `AI_RERANK_HOST` / `AI_LLM_HOST` 是否能从 Java 容器访问。
2. AI 节点 `/api/ai/health` 是否返回对应能力 enabled。
3. Ollama `11434` 是否监听 `0.0.0.0`。
4. `LLM_API_URL` 是否指向 `http://10.0.0.12:11434/v1/chat/completions`。

## 十二、入库链路验证

上传文档后观察：

```bash
docker logs -f kb-ai-worker
```

期望只有 `kb-ai-worker` 消费 Redis 文档任务。`kb-ai-embedding` 和 `kb-ai-rerank` 不应消费入库任务。

如果多个 AI 节点同时消费任务，说明有在线节点误配置：

```env
AI_START_WORKER=true
```

必须改回：

```env
AI_START_WORKER=false
```

## 十三、后续扩容方式

后续新增同规格服务器时，按瓶颈扩容：

| 瓶颈 | 扩容方式 |
|---|---|
| `/vector/dual` 慢 | 新增 `ai-embedding` 节点 |
| `/rerank` 慢 | 新增 `ai-rerank` 节点 |
| 问答首 token 慢 | 新增 Ollama 节点 |
| 文档入库慢 | 新增 `ai-worker` 节点 |
| OCR 慢 | 新增 OCR worker，或者把 OCR 和入库 Embedding 再拆开 |

第一阶段不建议马上做复杂负载均衡。先用固定路由稳定运行：

```text
AI_EMBEDDING_HOST -> 单个在线 embedding 节点
AI_RERANK_HOST    -> 单个 rerank 节点
AI_LLM_HOST       -> 单个 llm proxy 节点
```

等压测确认瓶颈后，再在 Java 网关或前置 Nginx 层做能力级负载均衡。

## 十四、推荐最终拓扑

```text
业务服务器 10.0.0.10
  Java / Frontend / Redis / ES / DB / MinIO

P4 服务器 A 10.0.0.11
  GPU0 -> ai-embedding:8001
  GPU1 -> ai-rerank:8002

P4 服务器 B 10.0.0.12
  GPU0 -> ollama:11434
  GPU1 -> ai-worker:8001
```

该拓扑的核心收益：

- 在线检索不被 OCR 和入库任务影响。
- LLM 生成不抢 Embedding / Rerank GPU。
- Rerank 故障可降级，不拖垮召回。
- 后续新增服务器时，可以按能力水平扩容。
