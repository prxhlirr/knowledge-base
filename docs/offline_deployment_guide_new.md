# 知识库系统 · 离线部署方案

> 版本 1.0 | 2026-03-23 | 内部保密文档  
> 目标服务器：Intel Xeon E5-2650 v4 × 2 路（48 超线程）| 128 GB RAM | Tesla P4 8 GB | 2 TB 磁盘  
> 操作系统环境：Linux | **CUDA 12.4** | **NVIDIA Driver 550.54.14**

---

## 目录

1. [系统架构总览](#一系统架构总览)
2. [Tesla P4 GPU 兼容性确认](#二tesla-p4-gpu-兼容性确认)
3. [服务器资源最优分配](#三服务器资源最优分配)
4. [离线资源准备清单](#四离线资源准备清单)
5. [配置文件修改清单](#五配置文件修改清单)
6. [分步部署操作手册](#六分步部署操作手册)
7. [部署验证清单](#七部署验证清单)
8. [性能预期与调优](#八性能预期与调优)

---

## 一、系统架构总览

所有组件部署于同一台服务器，通过 `127.0.0.1` 互相通信：

| 组件 | 端口 | 技术栈 | 职责 |
|--|--|--|--|
| 前端（Nginx） | 80 | Vue 3 + Nginx | 静态资源 + `/api` 反向代理 |
| Java 检索服务 | 8080 | Spring Boot 2.3 | 混合检索主流程（RRF/HyDE/ColBERT 调度） |
| Python AI 服务 | 8001 | FastAPI + ONNX Runtime | BGE-M3 嵌入、ColBERT 精排 |
| Ollama LLM | 11434 | Ollama + qwen2.5:7b | HyDE 扩写、意图改写、LLM 重排 |
| Elasticsearch | 9200 | ES 8.6.2 + IK 分词 | BM25 全文 + KNN 向量检索 |
| PostgreSQL | 5432 | PostgreSQL 14 | 元数据、租户策略、调参配置 |
| Redis | 6379 | Redis 7.0 | 搜索结果缓存 |
| MinIO | 9000 | MinIO latest | 原始文档对象存储 |

---

## 二、Tesla P4 GPU 兼容性确认

> [!WARNING]
> Tesla P4 **被动散热**，必须安装于有强制进风的机架位，否则过热降频至性能低于 CPU

### 2.1 CUDA 12.4 + Driver 550.54.14 兼容矩阵

| 组件 | 最低要求 | 目标环境 | 兼容? |
|--|--|--|--|
| Tesla P4（Pascal CC 6.1） | CUDA ≥ 11.0 | CUDA 12.4 | ✅ Pascal 在 CUDA 12.x 中未被弃用 |
| NVIDIA Driver | ≥ 525.60（CUDA 12.0） | 550.54.14 | ✅ 满足最低要求 |
| onnxruntime-gpu | ≥ 1.17.0 | 安装最新版 | ✅ 1.17+ 原生支持 CUDA 12.x |
| Ollama | 任意当前版本 | latest | ✅ 自动检测 CUDA 12.4 |
| FP16 Tensor Core | Volta+ 架构 | Pascal 架构 | ❌ 不支持，仅 FP32 + INT8 有效 |

> **结论：CUDA 12.4 + Driver 550.54.14 完全兼容项目所有组件，无需任何额外适配。**

### 2.2 VRAM 分配预算（Tesla P4 总量 8 GB）

| 组件 | VRAM 占用 | 备注 |
|--|--|--|
| CUDA 上下文 + 驱动开销 | ~400 MB | 固定占用 |
| BGE-M3 INT8 嵌入模型 | ~500 MB | 优先加载 `model_int8.onnx` |
| BGE-Reranker-v2-m3 INT8 | ~250 MB | 优先加载 `model_int8.onnx` |
| qwen2.5:7b Q4_K_M（Ollama） | ~4,700 MB | Ollama 默认 4-bit 量化 |
| 批推理缓冲区（峰值） | ~500 MB | 并发推理临时占用 |
| **合计 / 余量** | **~6,350 MB / ~1,650 MB** | ✅ 8 GB VRAM 可满足 |

> **备选**：若推理速度不达标，降级为 `qwen2.5:3b`（~2 GB VRAM），总 VRAM 降至 ~3.5 GB。

---

## 三、服务器资源最优分配

### 3.1 RAM 分配（总量 128 GB）

| 服务 | 分配 | 配置参数 | 依据 |
|--|--|--|--|
| Elasticsearch JVM 堆 | **32 GB** | `-Xms32g -Xmx32g` | ≤ 总内存 50%，且 ≤ 32 GB（JVM 指针压缩上限） |
| ES Lucene 页缓存（OS） | 32 GB | （OS 自动管理） | ES 重度依赖 OS 文件缓存 |
| PostgreSQL | **16 GB** | `shared_buffers=16GB` | 约 25% 总内存 |
| Redis | **4 GB** | `maxmemory 4gb` | 搜索结果缓存上限 |
| Java Spring Boot | **4 GB** | `-Xms3g -Xmx4g` | 含 Metaspace ~1 GB |
| Python AI 服务 | **8 GB** | `AI_CPU_THREADS=20` | 模型在 GPU，RAM 处理批次数据 |
| Ollama | **4 GB** | `OLLAMA_NUM_PARALLEL=2` | 上下文窗口 + 并发请求 |
| MinIO | **2 GB** | `mem_limit: 2g` | 大对象操作缓冲 |
| OS + Swap | **16 GB** | `swapsize=16G` | 内核预留 |
| **合计 / 余量** | **~118 GB / ~10 GB** | — | 充足缓冲 |

### 3.2 CPU 线程分配（48 超线程 = 24 物理核）

| 服务 | 线程配置 | 配置参数 |
|--|--|--|
| Elasticsearch | 12 核（自动派生线程池） | `processors: 12` |
| Python AI（CPU 回退推理） | 20 线程 | `AI_CPU_THREADS=20` |
| Java JVM GC | 自动（G1GC） | `-XX:ParallelGCThreads=12` |
| PostgreSQL 并行查询 | 12 工作进程 | `max_worker_processes=12` |
| Ollama 并发请求 | 2 个并发 | `OLLAMA_NUM_PARALLEL=2` |

### 3.3 存储布局（2 TB）

| 目录 | 分配空间 | 内容 |
|--|--|--|
| `/opt/knowledge-base/` | 50 GB | 项目代码、日志 |
| `/opt/kb-models/onnx_native/` | 20 GB | BGE-M3 + Reranker ONNX 模型 |
| `/opt/ollama-data/models/` | 10 GB | qwen2.5:7b 权重 |
| ES 数据卷（`esdata`） | **800 GB** | 全文索引 + 向量索引（~300 万 chunks）|
| PG 数据卷（`pgdata`） | 100 GB | 元数据、审计日志 |
| MinIO 数据卷（`miniodata`） | **500 GB** | 原始文档存储 |
| OS + 系统日志 | ~520 GB | 系统占用 + 余量 |

> ES 索引约为原始文档的 3~5 倍（含向量、IK 分词、BM25 字段）。800 GB 可支撑约 300 万知识库 chunk。

---

## 四、离线资源准备清单

> [!IMPORTANT]
> 以下所有资源须在**联网机器**上提前下载，通过离线介质拷贝到目标服务器

### 4.1 Docker 镜像

| 镜像 | 版本 | 大小参考 |
|--|--|--|
| `elasticsearch` | 8.6.2 | ~1.2 GB |
| `postgres` | 14-alpine | ~100 MB |
| `redis` | 7.0-alpine | ~30 MB |
| `minio/minio` | latest | ~100 MB |
| `ollama/ollama` | latest | ~2 GB |

**联网机器导出：**
```bash
docker save \
  docker.elastic.co/elasticsearch/elasticsearch:8.6.2 \
  postgres:14-alpine redis:7.0-alpine \
  minio/minio:latest ollama/ollama:latest \
  | gzip > kb-images.tar.gz
```

**目标服务器导入：**
```bash
docker load -i kb-images.tar.gz
```

### 4.2 AI 模型文件

| 模型 | 大小 | 路径（`MODEL_BASE_PATH` 下） |
|--|--|--|
| BGE-M3 `model_int8.onnx`（**优先**） | ~1.0 GB | `bge-m3/model_int8.onnx` |
| BGE-M3 `model.onnx`（备用） | ~1.2 GB | `bge-m3/model.onnx` |
| BGE-Reranker INT8（**优先**） | ~600 MB | `bge-reranker-v2-m3/onnx/model_int8.onnx` |
| BGE-Reranker FP32（备用） | ~1.2 GB | `bge-reranker-v2-m3/onnx/model.onnx` |
| qwen2.5:7b Q4_K_M | ~4.7 GB | `~/.ollama/models/`（Ollama 管理） |

**Ollama 模型离线迁移：**
```bash
# 联网机器：打包 Ollama 模型目录
tar czf ollama-models.tar.gz ~/.ollama/models/

# 目标服务器：解压
mkdir -p ~/.ollama && tar xzf ollama-models.tar.gz -C ~/
```

### 4.3 Python 依赖

> [!CAUTION]
> `onnxruntime`（CPU 版）必须替换为 `onnxruntime-gpu`，否则 GPU 无法生效！

```bash
# 联网机器：下载所有依赖包
pip download -r requirements.txt -d ./pip-offline-pkgs/

# onnxruntime-gpu (CUDA 12.x 版本) 需单独从专用源下载
pip download onnxruntime-gpu -d ./pip-offline-pkgs/ \
  --extra-index-url https://aiinfra.pkgs.visualstudio.com/PublicPackages/_packaging/onnxruntime-cuda-12/pypi/simple/

# 目标服务器：安装（离线）
pip uninstall onnxruntime -y
pip install --no-index --find-links=./pip-offline-pkgs/ -r requirements.txt

# 验证 CUDA Provider 生效
python -c "import onnxruntime; print(onnxruntime.get_available_providers())"
# 期望输出：['CUDAExecutionProvider', 'CPUExecutionProvider']
```

### 4.4 Java Maven 依赖

```bash
# 联网机器：下载离线仓库
mvn dependency:go-offline -Dmaven.repo.local=./offline-maven-repo
zip -r offline-maven-repo.zip offline-maven-repo/

# 目标服务器：构建时指定本地仓库
mvn package -DskipTests -Dmaven.repo.local=./offline-maven-repo
```

---

## 五、配置文件修改清单

> [!WARNING]
> 标注「**必修改**」的项目保留默认值将导致安全漏洞或连接失败！

### 5.1 [java_service/src/main/resources/application.yml](file:///e:/project/AI/knowledge-base/java_service/src/main/resources/application.yml)

| 配置项 | 默认值（开发） | 目标值 | 级别 |
|--|--|--|--|
| `spring.datasource.password` | `liyz` | `<强密码>` | **必修改** |
| `minio.accessKey` | `minioadmin` | `<16位+随机>` | **必修改** |
| `minio.secretKey` | `minioadmin` | `<24位+随机>` | **必修改** |
| `kb.internal.token` | `kb-dev-token-...` | `<32字符随机>` | **必修改** |
| `jwt.secret` | `default-dev-secret-...` | `<32字符随机>` | **必修改** |
| `jwt.dev-mode` | `true` | `false` | **必修改** |
| `search.trust-gateway-headers` | `false` | `true` | **必修改** |
| `cors.allowed-origins` | `localhost:5173,...` | 服务器 IP 或域名 | **必修改** |
| `search.total.sla-ms` | `5000` | `8000`（P4 推理较慢） | 建议修改 |
| `spring.datasource.url` | `localhost:5432` | `127.0.0.1:5432` | 按需 |
| `elasticsearch.host` | `localhost` | `127.0.0.1` | 按需 |

### 5.2 [ai_service/.env](file:///e:/project/AI/knowledge-base/ai_service/.env)（需**新建**）

```ini
# ONNX 模型根目录（绝对路径）
MODEL_BASE_PATH=/opt/kb-models/onnx_native

# Ollama LLM 接口
LLM_API_URL=http://127.0.0.1:11434/v1/chat/completions
LLM_MODEL=qwen2.5:7b
HYDE_LLM_MODEL=qwen2.5:7b
RERANKER_LLM_MODEL=qwen2.5:7b

# CPU 回退线程数（建议 = 物理核数 × 0.8）
AI_CPU_THREADS=20

# GPU 设备 ID
ORT_CUDA_DEVICE_ID=0
```

### 5.3 `docker-compose-prod.yml` 关键参数

```yaml
elasticsearch:
  environment:
    - "ES_JAVA_OPTS=-Xms32g -Xmx32g"
    - processors=12

postgres:
  command: >
    postgres
    -c shared_buffers=16GB
    -c effective_cache_size=32GB
    -c max_worker_processes=12
    -c max_parallel_workers=12

redis:
  command: redis-server --maxmemory 4gb --maxmemory-policy allkeys-lru --requirepass <密码>

ollama:
  environment:
    - OLLAMA_NUM_GPU=1
    - OLLAMA_NUM_PARALLEL=2
    - OLLAMA_MAX_LOADED_MODELS=1
```

### 5.4 Nginx 生产配置（`/etc/nginx/conf.d/knowledge-base.conf`）

```nginx
server {
    listen 80;
    server_name <服务器IP或域名>;

    root /opt/kb-frontend/dist;
    index index.html;
    try_files $uri $uri/ /index.html;

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

---

## 六、分步部署操作手册

### Step 1：确认 CUDA 环境

```bash
nvidia-smi
# 期望：NVIDIA-SMI 550.54.14  Driver Version: 550.54.14  CUDA Version: 12.4
#       GPU 名称：Tesla P4

# 确认计算能力
nvidia-smi --query-gpu=compute_cap --format=csv,noheader
# 期望：6.1
```

### Step 2：解压项目和模型

```bash
# 解压项目
tar xzf knowledge-base-offline.tar.gz -C /opt/knowledge-base/

# 解压 AI 模型
mkdir -p /opt/kb-models
tar xzf onnx-models.tar.gz -C /opt/kb-models/

# 解压 Ollama 模型
mkdir -p ~/.ollama
tar xzf ollama-models.tar.gz -C ~/
```

### Step 3：导入 Docker 镜像并启动中间件

```bash
# 导入镜像
docker load -i kb-images.tar.gz

# 启动中间件
cd /opt/knowledge-base
docker compose -f docker-compose-prod.yml up -d

# 确认所有容器正常运行
docker ps
# 期望：5 个容器均为 Up 状态（es86, database_pg, redis_cache, minio_storage, ollama_llm）
```

### Step 4：初始化数据库

```bash
# 建表
psql -h 127.0.0.1 -U kb_admin -d knowledge_base \
  -f sql/init.sql

psql -h 127.0.0.1 -U kb_admin -d knowledge_base \
  -f java_service/src/main/resources/db/sys_tenant_policy.sql

psql -h 127.0.0.1 -U kb_admin -d knowledge_base \
  -f java_service/src/main/resources/db/sys_ai_tuning_config.sql

# 创建 ES 索引（含 IK 分词器）
curl -X PUT "http://127.0.0.1:9200/kb_document_v1" \
     -H "Content-Type: application/json" \
     -d @es_index_mapping.json
```

### Step 5：构建并启动 Java 服务

```bash
cd /opt/knowledge-base/java_service

# 离线构建
mvn package -DskipTests -Dmaven.repo.local=./offline-maven-repo

# 启动
nohup java \
  -Xms3g -Xmx4g \
  -XX:+UseG1GC \
  -XX:ParallelGCThreads=12 \
  -XX:ConcGCThreads=4 \
  -jar target/knowledge-base-1.0.0-SNAPSHOT.jar \
  > logs/java.log 2>&1 &

echo "Java PID: $!"
```

### Step 6：安装 GPU onnxruntime 并启动 Python AI 服务

```bash
cd /opt/knowledge-base/ai_service

# 替换为 GPU 版 onnxruntime（关键步骤）
pip uninstall onnxruntime -y
pip install --no-index --find-links=./pip-offline-pkgs/ -r requirements.txt

# 验证 CUDA Provider
python -c "import onnxruntime; print(onnxruntime.get_available_providers())"
# 必须包含 CUDAExecutionProvider，否则检查 CUDA 驱动安装

# 创建 .env 配置文件（参考第五章）
# 启动
nohup python main.py > logs/ai.log 2>&1 &
echo "AI Service PID: $!"
```

### Step 7：部署前端

```bash
# 将构建好的 dist/ 部署到 Nginx 目录
cp -r /opt/knowledge-base/frontend/dist /opt/kb-frontend/

# 配置并重载 Nginx
cp knowledge-base.conf /etc/nginx/conf.d/
nginx -t && systemctl reload nginx
```

### Step 8：验证所有服务

```bash
# 检查各服务健康状态（8 项）
curl http://127.0.0.1:9200/_cluster/health     # ES
curl http://127.0.0.1:8080/actuator/health      # Java
curl http://127.0.0.1:8001/api/ai/health        # Python AI
curl http://127.0.0.1:11434/api/tags            # Ollama
curl http://127.0.0.1:9000/minio/health/live    # MinIO
```

---

## 七、部署验证清单

| 验证项 | 命令 | 期望结果 |
|--|--|--|
| GPU 驱动 | `nvidia-smi` | Tesla P4, Driver 550.54.14 |
| AI 服务 GPU 模式 | `curl .../api/ai/health` | `"acceleration":"CUDA"` |
| VRAM 占用（满载后） | `nvidia-smi --query-gpu=memory.used --format=csv` | ≤ 6,500 MiB |
| Ollama GPU 推理 | 推理期间观察 `nvidia-smi` | GPU 利用率 > 50% |
| Elasticsearch | `curl .../\_cluster/health` | `status: green 或 yellow` |
| Java 服务健康 | `curl .../actuator/health` | `status: UP` |
| 端到端搜索 | `POST /api/v1/search` | 结果 < 8s 返回 |
| MinIO 存储 | 访问 `:9001` 控制台 | `knowledge-base` bucket 存在 |

---

## 八、性能预期与调优

### 8.1 性能对比

| 指标 | 开发机（RTX 4060） | Tesla P4（预期） | 说明 |
|--|--|--|--|
| BGE-M3 嵌入耗时 | ~80ms | ~120-180ms | P4 带宽较低（GDDR5 vs GDDR6） |
| ColBERT 重排（10 文档） | ~150ms | ~250-400ms | P4 FP32 性能 |
| qwen2.5:7b 推理 | ~3s/次 | ~5-8s/次 | P4 Pascal 无 Tensor Core |
| 端到端搜索 | ~1.5s | ~3-6s | 建议 SLA 调至 8000ms |
| ES 全文检索 | ~50ms | **~25ms（更快）** | 32 GB 堆 + 48 核优势 |

### 8.2 常见问题排查

| 问题 | 原因 | 解决方案 |
|--|--|--|
| AI 服务 `acceleration=CPU` | `onnxruntime-gpu` 未安装 | `pip uninstall onnxruntime && pip install onnxruntime-gpu` |
| 搜索 SLA 超时 | P4 推理较慢 | 设置 `search.total.sla-ms=10000` |
| VRAM 不足 OOM | qwen 7b + ONNX 超 8 GB | 降级至 `qwen2.5:3b`，或减少 `OLLAMA_NUM_PARALLEL` |
| ES 连接拒绝 | 容器未启动或配置错误 | `docker logs es86`，检查 `elasticsearch.host` |
| 文件上传失败 | MinIO Bucket 未创建 | 登录 `:9001` 手动创建 `knowledge-base` bucket |
| PostgreSQL 连接失败 | 密码不匹配 | 确认 [application.yml](file:///e:/project/AI/knowledge-base/java_service/src/main/resources/application.yml) 与 docker-compose 中 `POSTGRES_PASSWORD` 一致 |
| Ollama GPU 未生效 | 容器未挂载 GPU 设备 | docker-compose 中添加 `deploy.resources.reservations.devices` |

---

*文档结束 | 版本 1.0 | 知识库系统离线部署方案 | 内部保密*
## 模型懒加载与文档噪声治理配置

离线部署默认建议关闭启动预加载，避免 AI 服务空闲时立即占用 GPU 显存：

```env
AI_MODEL_PRELOAD=false
AI_MODEL_IDLE_UNLOAD=true
AI_MODEL_IDLE_TTL_SECONDS=1800
AI_MODEL_IDLE_CHECK_INTERVAL_SECONDS=60

DOC_NOISE_FILTER_ENABLED=true
PDF_NOISE_FILTER_ENABLED=true
PDF_NOISE_MAX_FULL_SCAN_PAGES=300
PDF_HEADER_FOOTER_TOP_RATIO=0.10
PDF_HEADER_FOOTER_BOTTOM_RATIO=0.10
COMMENT_INDEX_POLICY=drop
FOOTNOTE_INDEX_POLICY=metadata_only
TEXT_CLEANER_ENABLE_CL1=false
```

注意：同一进程内释放 ONNX Runtime / CUDA 对象后，系统显存显示不一定立即下降。需要强保证释放时，应使用独立 GPU Worker 进程并让其空闲退出。
