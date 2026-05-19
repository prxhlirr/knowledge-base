# 离线 Docker Compose 显存释放改造实施计划

## 背景

当前 AI 服务已经实现进程内懒加载和空闲卸载：

- `AI_MODEL_PRELOAD=false` 时，服务启动不主动加载 embedding/rerank 模型。
- 首次调用 `encode/rerank` 时才加载对应模型。
- 空闲超过 `AI_MODEL_IDLE_TTL_SECONDS` 后调用 `model_manager.unload_all()`。

但该方案只能释放 Python 持有的模型对象，不保证 CUDA 上下文立即释放。因此 `nvidia-smi` 中看到的显存可能仍然保持占用，直到 Python 进程退出。

若生产环境要求“空闲时显存确定归还”，需要将 GPU 推理从主 AI 服务中拆到独立 GPU Worker 进程，并让 Worker 空闲退出。

## 目标

分两阶段改造离线部署：

1. **阶段一：Compose 启用懒加载配置**
   - 不拆服务。
   - 修改 `docker-compose-offline.yml` 中 AI 服务环境变量。
   - 达到“启动不占显存、首次推理才加载”的效果。
   - 不保证空闲后 `nvidia-smi` 显存一定下降。

2. **阶段二：独立 GPU Worker**
   - 主 AI 服务常驻 CPU，负责解析、任务队列、ES、LLM 转发。
   - GPU Worker 独立持有 ONNX Runtime CUDA session。
   - Worker 空闲超时后进程退出，从而确定释放 CUDA 上下文和显存。

## 不做事项

本计划不修改业务检索策略、不修改 Java 调用 AI 服务的外部接口、不修改前端。

阶段二实施前，仍需先补充 GPU Worker 代码和主服务远程调用能力。不能仅靠 compose 完成 Worker 拆分。

---

## 阶段一：离线 Compose 接入懒加载配置

### 修改文件

- `docker-compose-offline.yml`

### 当前问题

当前离线 compose 中 `knowledge-base-ai` 服务整体处于注释状态。若现场启用 AI 服务，需要确保以下环境变量存在：

```env
AI_MODEL_PRELOAD=false
AI_MODEL_IDLE_UNLOAD=true
AI_MODEL_IDLE_TTL_SECONDS=1800
AI_MODEL_IDLE_CHECK_INTERVAL_SECONDS=60
```

否则容器启动时可能仍然预加载模型，违背“启动不占显存”的目标。

### 建议配置

若现场使用 GPU 镜像，建议启用或调整 AI 服务为：

```yaml
  knowledge-base-ai:
    image: knowledge-base-ai:gpu
    container_name: ai-service
    ports:
      - "8001:8001"
    volumes:
      - ./models:/app/models
    environment:
      - MODEL_BASE_PATH=/app/models/onnx_native
      - PADDLE_OCR_MODEL_DIR=/app/models/paddle_ocr

      # 模型懒加载与空闲卸载
      - AI_MODEL_PRELOAD=false
      - AI_MODEL_IDLE_UNLOAD=true
      - AI_MODEL_IDLE_TTL_SECONDS=1800
      - AI_MODEL_IDLE_CHECK_INTERVAL_SECONDS=60

      # 文档噪声治理
      - DOC_NOISE_FILTER_ENABLED=true
      - PDF_NOISE_FILTER_ENABLED=true
      - PDF_NOISE_MAX_FULL_SCAN_PAGES=300
      - PDF_HEADER_FOOTER_TOP_RATIO=0.10
      - PDF_HEADER_FOOTER_BOTTOM_RATIO=0.10
      - COMMENT_INDEX_POLICY=drop
      - FOOTNOTE_INDEX_POLICY=metadata_only
      - TEXT_CLEANER_ENABLE_CL1=false

      # 中间件连接
      - REDIS_HOST=redis_cache
      - REDIS_PORT=6379
      - REDIS_PASSWORD=liyz12345
      - ES_HOST=http://elasticsearch:9200
      - JAVA_SERVICE_HOST=http://kb-java:8080
      - JAVA_API_BASE=http://kb-java:8080/api/v1
      - KB_INTERNAL_TOKEN=kb-dev-token-change-me-in-prod

      # NVIDIA 容器运行时
      - NVIDIA_VISIBLE_DEVICES=all
      - NVIDIA_DRIVER_CAPABILITIES=compute,utility
    gpus: all
    depends_on:
      - redis_cache
      - elasticsearch
    restart: always
```

如果现场 Docker Compose 版本不支持 `gpus: all`，使用：

```yaml
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: all
              capabilities: [gpu]
```

### 阶段一验证

1. 启动服务：

```bash
docker compose -f docker-compose-offline.yml up -d knowledge-base-ai
```

2. 查看日志，确认出现：

```text
AI_MODEL_PRELOAD=false，模型将在首次 encode/rerank 时懒加载
```

3. 启动后立即查看 GPU：

```bash
nvidia-smi
```

预期：AI 服务不应因 embedding/rerank 预加载而占用大块显存。

4. 调用向量接口：

```bash
curl -X POST http://localhost:8001/api/ai/vector/query \
  -H "Content-Type: application/json" \
  -d '{"text":"测试查询"}'
```

预期：首次请求触发 embedding 模型加载。

5. 等待 TTL 后查看 health：

```bash
curl http://localhost:8001/api/ai/health
```

预期：

- `embedding_loaded=false`
- `reranker_loaded=false`

注意：`nvidia-smi` 显存可能不立即下降，这不作为阶段一失败条件。

---

## 阶段二：独立 GPU Worker 架构

## 架构目标

拆分后容器结构：

```text
knowledge-base-java
  -> knowledge-base-ai:8001
       - 文档解析
       - 入库任务消费
       - ES 写入
       - LLM 转发
       - 不直接加载 GPU 模型

knowledge-base-ai
  -> ai-gpu-worker:8011
       - embedding
       - sparse embedding
       - dual embedding
       - rerank
       - 空闲超时后退出
```

## 需要新增或修改的代码

### 新增文件

- `ai_service/gpu_worker.py`

职责：

- 提供独立 FastAPI 服务。
- 暴露以下接口：
  - `POST /worker/vector/query`
  - `POST /worker/vector/sparse`
  - `POST /worker/vector/dual`
  - `POST /worker/rerank`
  - `GET /worker/health`
- 内部使用 `model_manager`。
- 支持 `WORKER_IDLE_EXIT_SECONDS`。
- 空闲超过阈值后主动 `os._exit(0)` 或优雅退出进程。

### 修改文件

- `ai_service/core/model_manager.py`
- `ai_service/main.py`

建议新增远程后端配置：

```env
AI_VECTOR_BACKEND=local
AI_GPU_WORKER_URL=http://ai-gpu-worker:8011
AI_GPU_WORKER_TIMEOUT_SECONDS=120
```

行为：

- `AI_VECTOR_BACKEND=local`：保持当前进程内推理。
- `AI_VECTOR_BACKEND=remote`：主 AI 服务调用 GPU Worker。

### 远程调用封装建议

新增或修改：

- `ai_service/core/gpu_worker_client.py`

职责：

- 封装 HTTP 调用。
- 处理连接失败、超时、错误码。
- 后续可扩展为“Worker 不存在时触发拉起”。

接口：

```python
class GpuWorkerClient:
    def encode_query(text, skip_instruction=False): ...
    def encode_sparse(text): ...
    def encode_dual(text, skip_instruction=False): ...
    def rerank(query, documents): ...
```

## 阶段二 Compose 方案

### 主 AI 服务

```yaml
  knowledge-base-ai:
    image: knowledge-base-ai:gpu
    container_name: ai-service
    ports:
      - "8001:8001"
    volumes:
      - ./models:/app/models
    environment:
      - MODEL_BASE_PATH=/app/models/onnx_native
      - AI_MODEL_PRELOAD=false
      - AI_VECTOR_BACKEND=remote
      - AI_GPU_WORKER_URL=http://ai-gpu-worker:8011

      - REDIS_HOST=redis_cache
      - REDIS_PORT=6379
      - REDIS_PASSWORD=liyz12345
      - ES_HOST=http://elasticsearch:9200
      - JAVA_SERVICE_HOST=http://kb-java:8080
      - JAVA_API_BASE=http://kb-java:8080/api/v1
      - KB_INTERNAL_TOKEN=kb-dev-token-change-me-in-prod
    depends_on:
      - redis_cache
      - elasticsearch
    restart: always
```

说明：

- 主服务如果镜像仍包含 GPU 依赖也可以运行，但不应声明 `gpus: all`。
- 更理想的是构建 CPU 版 AI 镜像，减少运行时 CUDA 依赖。

### GPU Worker

```yaml
  ai-gpu-worker:
    image: knowledge-base-ai:gpu
    container_name: ai-gpu-worker
    command: python -m uvicorn gpu_worker:app --host 0.0.0.0 --port 8011 --workers 1
    volumes:
      - ./models:/app/models
    environment:
      - MODEL_BASE_PATH=/app/models/onnx_native
      - AI_MODEL_PRELOAD=false
      - WORKER_IDLE_EXIT_SECONDS=1800
      - NVIDIA_VISIBLE_DEVICES=all
      - NVIDIA_DRIVER_CAPABILITIES=compute,utility
    gpus: all
    restart: "no"
```

关键点：

- `restart: "no"` 是必须考虑的配置。如果设置为 `always`，Worker 空闲退出后会立刻被 Docker 拉起，显存又会重新占用。
- 但 `restart: "no"` 也意味着 Worker 退出后不会自动恢复。需要主服务具备拉起 Worker 的能力，或现场通过 supervisor/systemd/运维脚本按需启动。

## Worker 拉起策略

### 方案 A：常驻 Worker，空闲只卸载模型

优点：

- Compose 简单。
- 主服务无需控制 Docker。

缺点：

- 不能保证 CUDA 上下文完全释放。

适用：

- 只要求启动不加载模型，不强求 `nvidia-smi` 下降。

### 方案 B：Worker 空闲退出，人工或运维系统拉起

优点：

- 显存可确定释放。
- 实现简单。

缺点：

- Worker 退出后首次请求可能失败，或需要运维系统提前拉起。

适用：

- 有外部调度系统的离线现场。

### 方案 C：主 AI 服务按需拉起 Worker

优点：

- 用户体验最好。
- 显存空闲释放也可靠。

缺点：

- 主 AI 容器需要访问 Docker socket 或宿主机控制接口。
- 安全风险较高，不建议默认启用。

如果采用该方案，compose 需要额外挂载：

```yaml
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

不建议在未做权限隔离的生产环境直接挂载 Docker socket。

## 阶段二验证

1. 启动主 AI 服务，不启动 Worker。
2. 调用 health，确认主服务不加载 GPU 模型。
3. 启动 Worker：

```bash
docker compose -f docker-compose-offline.yml up -d ai-gpu-worker
```

4. 调用向量接口，确认请求经 Worker 返回。
5. 查看 `nvidia-smi`，确认 Worker 进程占用显存。
6. 等待 `WORKER_IDLE_EXIT_SECONDS`。
7. 确认 Worker 容器退出。
8. 再看 `nvidia-smi`，确认显存释放。

---

## 推荐实施顺序

1. 先实施阶段一 compose 环境变量改造。
2. 在离线环境验证启动不预加载模型。
3. 如果业务接受“进程内卸载不保证 `nvidia-smi` 下降”，阶段一即可。
4. 如果必须强释放显存，再实施阶段二：
   - 新增 `gpu_worker.py`
   - 新增 `gpu_worker_client.py`
   - 主服务支持 `AI_VECTOR_BACKEND=remote`
   - 修改 `docker-compose-offline.yml` 增加 `ai-gpu-worker`
5. 阶段二不要直接挂 Docker socket，除非明确接受安全风险。

## 审核结论

本计划建议先进入阶段一。阶段二属于架构拆分，需要代码和 compose 同步改造，不能只改 compose。
