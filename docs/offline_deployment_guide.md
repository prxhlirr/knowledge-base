# 知识库系统 · 离线部署指南

> 编写日期：2026-03-23 | 适用版本：knowledge-base 1.0.x  
> 适用场景：私有政务内网、无互联网访问的离线服务器

---

## 一、硬编码清单与配置迁移

> [!IMPORTANT]
> 以下列表是**必须修改**的配置项，否则离线环境将无法连接各组件。

### 1.1 Java 服务（[java_service/src/main/resources/application.yml](file:///e:/project/AI/knowledge-base/java_service/src/main/resources/application.yml)）

所有 Java 端配置已通过 `@Value` 注解读取配置文件，**直接修改 [application.yml](file:///e:/project/AI/knowledge-base/java_service/src/main/resources/application.yml)** 即可。

| 配置项 | 默认值（开发） | 离线部署修改说明 |
|--|--|--|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/knowledge_base` | 改为目标机 PG 地址 |
| `spring.datasource.username` | `postgres` | 改为实际账户名 |
| `spring.datasource.password` | `liyz` | **必须** 改为强密码 |
| `spring.redis.host` | `localhost` | 改为 Redis 服务器 IP |
| `spring.redis.password` | (空) | 若 Redis 启用认证则填入 |
| `elasticsearch.host` | `localhost` | 改为 ES 服务器 IP |
| `elasticsearch.port` | `9200` | 按实际端口 |
| `elasticsearch.username` | (空) | ES 启用 xpack 安全时填入 |
| `elasticsearch.password` | (空) | ES 启用 xpack 安全时填入 |
| `ai.service.host` | `http://127.0.0.1:8001` | 若 AI 服务独立部署改为对应 IP |
| `minio.endpoint` | `http://localhost:9000` | 改为 MinIO 服务地址 |
| `minio.accessKey` | `minioadmin` | **必须** 改为强密钥 |
| `minio.secretKey` | `minioadmin` | **必须** 改为强密钥 |
| `kb.internal.token` | `kb-dev-token-change-me-in-prod` | **必须** 改为随机 32 字符 |
| `search.trust-gateway-headers` | `false` | 生产改为 `true` 并配置 JWT |
| `jwt.dev-mode` | `true` | 生产改为 `false` |
| `jwt.secret` | `default-dev-secret-must-change-in-prod-32chars` | **必须** 改为随机 32+ 字符 |
| `cors.allowed-origins` | `http://localhost:5173,...` | 改为实际部署的前端域名/IP |
| `spring.kafka.bootstrap-servers` | `localhost:9092` | 若不用 Kafka 可忽略 |

### 1.2 Python AI 服务（`ai_service/` —— 新建 `.env` 文件）

> [!NOTE]
> Python 端当前使用 `os.getenv()` 读取环境变量，**没有 `.env` 文件**。离线部署须新建。

在 `ai_service/` 目录下创建 `.env` 文件：

```ini
# ── LLM 大模型接入（Ollama 本地服务）──────────────────────────────
LLM_API_URL=http://127.0.0.1:11434/v1/chat/completions
LLM_MODEL=qwen2.5:7b
HYDE_LLM_MODEL=qwen2.5:7b
RERANKER_LLM_MODEL=qwen2.5:7b

# ── ONNX 模型根目录（绝对路径）──────────────────────────────────────
# 目录结构：
#   <MODEL_BASE_PATH>/bge-m3/model.onnx
#   <MODEL_BASE_PATH>/bge-m3/model_int8.onnx
#   <MODEL_BASE_PATH>/bge-reranker-v2-m3/onnx/model.onnx
#   <MODEL_BASE_PATH>/bge-reranker-v2-m3/onnx/model_int8.onnx
MODEL_BASE_PATH=/opt/kb-models/onnx_native

# ── CPU 线程数（无 GPU 时生效）──────────────────────────────────────
AI_CPU_THREADS=16

# ── GPU 设备 ID（多卡时指定，单卡默认 0）────────────────────────────
ORT_CUDA_DEVICE_ID=0
```

> 启动 AI 服务时加载 `.env`：
> ```bash
> # Linux 方式
> export $(cat .env | xargs) && python main.py
> # 或直接使用 python-dotenv（在 main.py 顶部加 load_dotenv()）
> ```

### 1.3 前端（[frontend/vite.config.js](file:///e:/project/AI/knowledge-base/frontend/vite.config.js) → 生产 Nginx 代理）

开发代理 `target: 'http://localhost:8080'` **仅影响 `npm run dev`**，  
生产 build 后由 **Nginx** 反向代理 `/api` 请求，开发配置无需改动。

**Nginx 生产配置示例**（`/etc/nginx/conf.d/knowledge-base.conf`）：
```nginx
server {
    listen 80;
    server_name <服务器IP或域名>;

    # 前端静态资源
    root /opt/kb-frontend/dist;
    index index.html;
    try_files $uri $uri/ /index.html;

    # 转发 /api 请求到 Java 服务
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

### 1.4 残留硬编码（工具类，不影响生产运行）

| 文件 | 位置 | 说明 |
|--|--|--|
| [DbInitializer.java](file:///e:/project/AI/knowledge-base/java_service/src/main/java/com/boyang/search/DbInitializer.java) | 第 9 行 `jdbc:postgresql://localhost:5432/...` | 仅本地初始化脚本，不进 WAR 包 |
| [DBUpdate.java](file:///e:/project/AI/knowledge-base/java_service/src/test/java/DBUpdate.java)（test） | 第 7 行 DB credentials | 测试工具类，不影响生产 |
| [EsMappingViewer.java](file:///e:/project/AI/knowledge-base/java_service/src/main/java/com/boyang/search/EsMappingViewer.java) | 第 14 行 `localhost:9200` | 仅本地调试脚本 |

---

## 二、离线环境资源清单

> [!IMPORTANT]
> 以下所有资源需**在有网络的机器上提前下载**，再拷贝到目标离线服务器。

### 2.1 软件运行时

| 软件 | 版本要求 | 下载地址 | 安装包大小参考 |
|--|--|--|--|
| **JDK** | 11 或 17（LTS） | adoptium.net → `.tar.gz` / `.msi` | ~200 MB |
| **Python** | 3.10 或 3.11 | python.org → 离线安装包 | ~30 MB |
| **Node.js** | 18 LTS（仅需在构建机上装） | nodejs.org | ~70 MB |
| **Maven** | 3.8+ | apache.org/maven | ~10 MB |
| **Nginx** | 1.24+ | nginx.org → zip | ~2 MB |

### 2.2 中间件（推荐 Docker 方式离线部署）

| 中间件 | 版本 | Docker 镜像 | 磁盘占用 |
|--|--|--|--|
| **Elasticsearch** | 8.6.2 | `docker.elastic.co/elasticsearch/elasticsearch:8.6.2` | ~1.2 GB |
| **IK 分词插件** | 8.6.2 | 随项目 [elasticsearch/elasticsearch-analysis-ik-8.6.2.zip](file:///e:/project/AI/knowledge-base/elasticsearch/elasticsearch-analysis-ik-8.6.2.zip) | ~10 MB |
| **PostgreSQL** | 14+ | `postgres:14-alpine` | ~100 MB |
| **Redis** | 7.0 | `redis:7.0-alpine` | ~30 MB |
| **MinIO** | Latest | `minio/minio:latest` | ~100 MB |
| **Ollama** | Latest | `ollama/ollama:latest` 或独立安装包 | ~2 GB |

**离线镜像导出（在有网络机器执行）：**
```bash
docker pull docker.elastic.co/elasticsearch/elasticsearch:8.6.2
docker pull postgres:14-alpine
docker pull redis:7.0-alpine
docker pull minio/minio:latest
docker pull ollama/ollama:latest

docker save docker.elastic.co/elasticsearch/elasticsearch:8.6.2 | gzip > es-8.6.2.tar.gz
docker save postgres:14-alpine | gzip > postgres-14.tar.gz
docker save redis:7.0-alpine | gzip > redis-7.tar.gz
docker save minio/minio:latest | gzip > minio.tar.gz
docker save ollama/ollama:latest | gzip > ollama.tar.gz
```

**在离线目标机导入：**
```bash
docker load -i es-8.6.2.tar.gz
docker load -i postgres-14.tar.gz
docker load -i redis-7.tar.gz
docker load -i minio.tar.gz
docker load -i ollama.tar.gz
```

### 2.3 AI 模型文件（最大资产，必须离线拷贝）

| 模型 | 用途 | 大小参考 | 获取方式 |
|--|--|--|--|
| **BAAI/bge-m3** ONNX | 嵌入向量 + ColBERT | ~4 GB（fp32）/ ~1 GB（int8） | HuggingFace / 已本地化在 `models/onnx_native/bge-m3/` |
| **BAAI/bge-reranker-v2-m3** ONNX | Reranker 精排 | ~2 GB（fp32）/ ~600 MB（int8） | HuggingFace / 已本地化在 `models/onnx_native/bge-reranker-v2-m3/` |
| **qwen2.5:7b** via Ollama | LLM HyDE/Rerank | ~4.7 GB | `ollama pull qwen2.5:7b`（执行后导出） |

**Ollama 模型离线迁移：**
```bash
# 有网机器：拉取并导出
ollama pull qwen2.5:7b
ollama save qwen2.5:7b -o qwen2.5-7b.ollama  # 实际是目录，打包
# 或直接打包 Ollama 数据目录
tar czf ollama-models.tar.gz ~/.ollama/models/  # Linux
# Windows: %USERPROFILE%\.ollama\models\

# 离线机器：导入
tar xzf ollama-models.tar.gz -C ~/.ollama/
```

### 2.4 Python 依赖包

```bash
# 在有网机器上：下载所有 .whl 包到本地
pip download -r requirements.txt -d ./pip-offline-pkgs/

# 打包
zip -r pip-offline-pkgs.zip pip-offline-pkgs/

# 离线机器：安装
pip install --no-index --find-links=./pip-offline-pkgs/ -r requirements.txt
```

**关键依赖**：`fastapi`, `uvicorn`, `onnxruntime-directml`（Win GPU）或 `onnxruntime`（CPU/Linux），`transformers`, `numpy`, `requests`, `opencc-python-reimplemented`, `pypinyin`, `python-dotenv`

### 2.5 Java Maven 依赖

```bash
# 在有网机器执行
mvn dependency:go-offline -Dmaven.repo.local=./offline-maven-repo
zip -r offline-maven-repo.zip offline-maven-repo/

# 部署时 pom.xml 添加本地仓库，或直接拷贝 ~/.m2/repository
```

---

## 三、离线部署步骤

### Step 1：准备服务器

**最低配置要求（CPU 推理）：**
- CPU：16 核（推荐 32 核）
- 内存：32 GB（BGE-M3 + Qwen2.5:7b 同时运行需 24 GB+）
- 磁盘：200 GB（模型 10 GB + ES 数据 + 文档存储）
- OS：CentOS 7+ / Ubuntu 20.04+ / Windows Server 2019+

**若有 GPU（NVIDIA）：**
- VRAM：≥ 8 GB（用于 BGE-M3 ONNX CUDA 推理）
- 安装 CUDA 驱动 + CUDA 11.8+

### Step 2：启动中间件

```bash
# 创建 docker-compose-prod.yml（参考下方），统一启动所有中间件
docker compose -f docker-compose-prod.yml up -d
```

**`docker-compose-prod.yml`** 完整版（取消注释并修改密码）：
```yaml
version: '3.8'
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.6.2
    container_name: es86
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
      - "ES_JAVA_OPTS=-Xms4g -Xmx4g"  # 根据内存调整，建议 4~8g
    ports:
      - "9200:9200"
    volumes:
      - esdata:/usr/share/elasticsearch/data
    restart: always

  postgres:
    image: postgres:14-alpine
    container_name: database_pg
    environment:
      POSTGRES_USER: kb_admin
      POSTGRES_PASSWORD: <强密码>    # ← 修改
      POSTGRES_DB: knowledge_base
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./sql/init.sql:/docker-entrypoint-initdb.d/init.sql:ro
    restart: always

  redis:
    image: redis:7.0-alpine
    container_name: redis_cache
    command: redis-server --requirepass <Redis密码>  # ← 修改
    ports:
      - "6379:6379"
    restart: always

  minio:
    image: minio/minio:latest
    container_name: minio_storage
    environment:
      MINIO_ROOT_USER: <AccessKey>    # ← 修改
      MINIO_ROOT_PASSWORD: <SecretKey>  # ← 修改，≥8字符
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - miniodata:/data
    command: server /data --console-address ":9001"
    restart: always

  ollama:
    image: ollama/ollama:latest
    container_name: ollama_llm
    ports:
      - "11434:11434"
    volumes:
      - ollamadata:/root/.ollama
    restart: always

volumes:
  esdata:
  pgdata:
  miniodata:
  ollamadata:
```

### Step 3：初始化数据库和 ES

```bash
# 3.1 执行 PostgreSQL 建表 SQL
psql -h <PG_IP> -U kb_admin -d knowledge_base -f sql/init.sql
psql -h <PG_IP> -U kb_admin -d knowledge_base -f java_service/src/main/resources/db/sys_tenant_policy.sql
psql -h <PG_IP> -U kb_admin -d knowledge_base -f java_service/src/main/resources/db/sys_ai_tuning_config.sql

# 3.2 创建 ES 索引（已有映射文件）
curl -X PUT "http://<ES_IP>:9200/kb_document_v1" \
     -H "Content-Type: application/json" \
     -d @es_index_mapping.json

# 3.3 在 Ollama 中加载模型（模型文件已离线拷贝）
curl http://<OLLAMA_IP>:11434/api/tags  # 验证已加载
```

### Step 4：修改配置文件

修改 `java_service/src/main/resources/application.yml` 中所有 IP/密码（参考第一章清单）。

创建 `ai_service/.env` 文件（参考第一章 1.2 节）。

### Step 5：构建并启动 Java 服务

```bash
cd java_service

# 离线构建（使用提前下载的 Maven 本地仓库）
mvn package -DskipTests -Dmaven.repo.local=./offline-maven-repo

# 启动（生产模式）
java -jar -Xms2g -Xmx4g target/knowledge-base-1.0.0-SNAPSHOT.jar \
     --spring.config.location=./src/main/resources/application.yml \
     --server.port=8080
```

### Step 6：启动 Python AI 服务

```bash
cd ai_service

# 安装依赖（离线）
pip install --no-index --find-links=./pip-offline-pkgs/ -r requirements.txt

# 启动（加载 .env）
python -m dotenv run -- uvicorn main:app --host 0.0.0.0 --port 8001 --workers 1
# 或直接设置环境变量后启动
export $(cat .env | grep -v '#' | xargs)
python main.py
```

### Step 7：构建并部署前端

```bash
# 在构建机（需 Node.js）执行
cd frontend
npm install
npm run build

# 将 dist/ 目录拷贝到服务器
scp -r dist/ user@<服务器IP>:/opt/kb-frontend/

# 配置 Nginx（参考第一章 1.3 节），然后
systemctl reload nginx
```

### Step 8：验证部署

```bash
# 检查各服务健康状态
curl http://<服务器IP>:9200/_cluster/health          # ES
curl http://<服务器IP>:8080/actuator/health           # Java
curl http://<服务器IP>:8001/api/ai/health             # Python AI
curl http://<服务器IP>:11434/api/tags                 # Ollama
curl http://<服务器IP>:9000/minio/health/live         # MinIO

# 执行一次搜索测试
curl -X POST http://<服务器IP>:8080/api/v1/search \
     -H "Content-Type: application/json" \
     -H "X-Search-AppCode: ADMIN_MASTER_KEY" \
     -d '{"queryText":"测试查询","pageSize":3}'
```

---

## 四、启停脚本建议

```bash
# start.sh（所有服务统一启动）
docker compose -f docker-compose-prod.yml up -d
sleep 10
# 后台启动 Java 服务
nohup java -jar -Xms2g -Xmx4g target/knowledge-base-*.jar > logs/java.log 2>&1 &
# 后台启动 Python AI 服务
cd ai_service && nohup python main.py > logs/ai.log 2>&1 &

# stop.sh
pkill -f "knowledge-base"
pkill -f "uvicorn"
docker compose -f docker-compose-prod.yml down
```

---

## 五、常见问题

| 问题 | 原因 | 解决 |
|--|--|--|
| AI 服务 500 / 模型未加载 | MODEL_BASE_PATH 路径错误 | 检查 `.env`，路径使用绝对路径 |
| 搜索返回 401 | AppCode 无效 | 检查 `sys_tenant_policy` 表是否有数据 |
| ES 连接拒绝 | IP/端口配置错误 | 检查 `elasticsearch.host` 配置 |
| LLM 调用超时 | Ollama 服务未启动或模型未加载 | 检查 `docker ps`，`curl :11434/api/tags` |
| 文件上传失败 | MinIO 未创建 Bucket | 登录 MinIO Console（:9001）创建 `knowledge-base` bucket |
| PostgreSQL 连接失败 | 密码不匹配 | 确认 `application.yml` 与 Docker 中 `POSTGRES_PASSWORD` 一致 |
