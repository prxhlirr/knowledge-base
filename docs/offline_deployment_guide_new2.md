# 知识库系统离线部署指南

> 目标环境：Linux x86_64 服务器，已预装 Docker 24+、Docker Compose 2+  
> 中间件（Elasticsearch 8.6.2 / PostgreSQL 14 / Redis 7 / MinIO）已在离线环境安装完毕  
> 本文档仅涉及三个自建应用服务的部署

---

## 一、资源清单

### 1.1 镜像文件（需传输到目标服务器）

| 文件 | 镜像名 | 大小 | 说明 |
|------|--------|------|------|
| `java_service/java-service.tar` | `knowledge-base-java:1.0.0` | ~186 MB | Spring Boot 网关服务 |
| `frontend/frontend.tar` | `knowledge-base-frontend:1.0.0` | ~20 MB | Vue3 前端（Nginx） |
| `ai_service/ai-service.tar` | `knowledge-base-ai:1.0.0` | ~1.4 GB | FastAPI AI 推理服务 |

### 1.2 配置文件（需传输 + 修改密码后使用）

| 文件 | 用途 |
|------|------|
| `java_service/config/application.yml` | Java 服务配置（数据库/Redis/ES 地址和密码） |
| `frontend/config/nginx.conf` | Nginx 配置（API 代理地址） |
| `ai_service/config/.env` | AI 服务配置（模型路径/Ollama/Redis） |

### 1.3 模型文件

| 目录 | 内容 | 说明 |
|------|------|------|
| `models/onnx_native/bge-m3/` | BGE-M3 ONNX 模型 | 向量化推理模型 |
| `models/onnx_native/bge-reranker-v2-m3/` | BGE-Reranker ONNX 模型 | 重排序模型 |
| Ollama 中的 `qwen2.5:7b` | 大语言模型 | 已在离线环境 Ollama 中，无需额外操作 |

### 1.4 数据库初始化脚本

| 文件 | 说明 |
|------|------|
| `sql/init_all_v4.sql` | PostgreSQL 全量建表 DDL（14 张表，幂等可重复执行） |

---

## 二、目标服务器目录结构

将以下文件/目录传输到目标服务器的**部署根目录**（例如 `/opt/knowledge-base/`）：

```
/opt/knowledge-base/
├── java_service/
│   └── config/
│       └── application.yml       ← 可编辑的 Java 配置
├── frontend/
│   └── config/
│       └── nginx.conf            ← 可编辑的 Nginx 配置
├── ai_service/
│   └── config/
│       └── .env                  ← 可编辑的 AI 服务配置
├── models/
│   └── onnx_native/
│       ├── bge-m3/               ← BGE-M3 模型文件
│       └── bge-reranker-v2-m3/   ← Reranker 模型文件
├── sql/
│   └── init_all_v4.sql
├── images/                       ← 镜像 tar 包存放目录
│   ├── java-service.tar
│   ├── frontend.tar
│   └── ai-service.tar
└── docker-compose-prod.yml
```

---

## 三、部署步骤

### 步骤 1：修改配置文件

部署前必须修改所有 `<CHANGE_ME_*>` 占位符。

#### 3.1 Java 服务配置
编辑 `java_service/config/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://<PG_HOST>:5432/knowledge_base  # ← 实际 PostgreSQL 地址
    username: kb_admin
    password: <实际PG密码>

  redis:
    host: <REDIS_HOST>     # ← 实际 Redis 地址
    password: <实际Redis密码>

elasticsearch:
  host: <ES_HOST>          # ← 实际 ES 地址

minio:
  endpoint: http://<MINIO_HOST>:9000
  accessKey: <MinIO AccessKey>
  secretKey: <MinIO SecretKey>

ai:
  service:
    host: http://kb-ai:8001   # 与 docker-compose 容器名保持一致（无需改）

kb:
  internal:
    token: <自定义内部凭证字符串>

jwt:
  dev-mode: false
  secret: <≥32位随机字符串>
```

#### 3.2 Nginx 配置
编辑 `frontend/config/nginx.conf`，修改 `proxy_pass` 指向实际 Java 服务：

```nginx
location /api/ {
    proxy_pass http://kb-java:8080;   # 同 docker-compose 网络内无需改
    # 若 Java 服务不在同一 compose 网络，改为实际 IP：
    # proxy_pass http://<JAVA_HOST>:8080;
}
```

#### 3.3 AI 服务配置
编辑 `ai_service/config/.env`：

```ini
ES_HOST=http://<ES_HOST>:9200
REDIS_HOST=<REDIS_HOST>
REDIS_PASSWORD=<实际Redis密码>
JAVA_API_BASE=http://kb-java:8080/api/v1
LLM_API_URL=http://<OLLAMA_HOST>:11434/v1/chat/completions
MODEL_BASE_PATH=/app/models/onnx_native   # 保持不变（容器内路径）
```

---

### 步骤 2：初始化数据库

```bash
# 连接 PostgreSQL，创建数据库和用户（如尚未创建）
psql -h <PG_HOST> -U postgres -c "CREATE USER kb_admin WITH PASSWORD '<实际PG密码>';"
psql -h <PG_HOST> -U postgres -c "CREATE DATABASE knowledge_base OWNER kb_admin;"

# 执行全量建表 DDL（幂等，可重复执行）
psql -h <PG_HOST> -U kb_admin -d knowledge_base -f /opt/knowledge-base/sql/init_all_v4.sql
```

---

### 步骤 3：创建 Elasticsearch 索引

```bash
# 创建知识库向量索引（使用项目根目录的 mapping 文件）
curl -X PUT "http://<ES_HOST>:9200/kb_document_v1" \
  -H "Content-Type: application/json" \
  -d @/opt/knowledge-base/es_index_mapping.json
```

---

### 步骤 4：加载 Docker 镜像

```bash
cd /opt/knowledge-base/images

docker load -i java-service.tar
docker load -i frontend.tar
docker load -i ai-service.tar

# 验证镜像加载成功
docker images | grep knowledge-base
```

预期输出：
```
knowledge-base-java      1.0.0   ...   498MB
knowledge-base-frontend  1.0.0   ...   ~50MB
knowledge-base-ai        1.0.0   ...   3.13GB
```

---

### 步骤 5：启动服务

```bash
cd /opt/knowledge-base

# 仅启动三个自建服务（中间件已单独运行）
docker compose -f docker-compose-prod.yml up -d java-service frontend ai-service

# 查看启动状态
docker compose -f docker-compose-prod.yml ps
```

---

### 步骤 6：验证服务健康

```bash
# Java 网关服务
curl http://localhost:8080/actuator/health
# 期望：{"status":"UP"}

# AI 服务
curl http://localhost:8001/api/ai/health
# 期望：{"status":"ok","model_loaded":true,...}

# 前端页面
curl -I http://localhost/
# 期望：HTTP/1.1 200 OK
```

---

## 四、docker-compose-prod.yml 配置说明

三个服务的关键 volume 挂载关系：

| 服务 | 宿主机路径 | 容器内路径 | 用途 |
|------|----------|-----------|------|
| java-service | `./java_service/config` | `/app/config` | Spring Boot 配置目录 |
| frontend | `./frontend/config/nginx.conf` | `/etc/nginx/conf.d/app.conf` | Nginx 配置 |
| ai-service | `./ai_service/config` | `/app/config` | `.env` 配置目录 |
| ai-service | `./models/onnx_native` | `/app/models/onnx_native` | ONNX 模型（只读挂载） |

> **配置热更新**：修改配置文件后，执行：
> - Java：`docker restart kb-java`
> - Nginx：`docker exec kb-frontend nginx -s reload`
> - AI：`docker restart kb-ai`（模型重新加载约需 60-90s）

---

## 五、常见问题

### Q1：AI 服务启动后模型加载失败
```bash
# 查看 AI 服务日志
docker logs kb-ai --tail 100

# 确认模型文件挂载成功
docker exec kb-ai ls /app/models/onnx_native/
# 应看到 bge-m3/ 和 bge-reranker-v2-m3/ 两个目录
```

### Q2：AI 服务使用 CPU 而非 GPU
检查 `ai_service/config/.env` 中的 GPU 配置：
```ini
USE_GPU=True
ORT_CUDA_DEVICE_ID=0
```
确认目标服务器已安装 `NVIDIA Container Toolkit`：
```bash
nvidia-smi
docker info | grep -i runtime
```

### Q3：Java 服务连接数据库失败
确认 `application.yml` 中的 host/port/密码正确，且 PostgreSQL 和 Redis 允许容器 IP 段访问：
```bash
docker logs kb-java --tail 50 | grep -E "ERROR|connect"
```

### Q4：前端 API 请求 502/504
检查 `nginx.conf` 中 `proxy_pass` 地址是否正确；确认 `kb-java` 容器在同一 Docker 网络中：
```bash
docker network inspect knowledge-base_default
```

---

## 六、服务依赖关系

```
前端(kb-frontend:80)
    └── Java网关(kb-java:8080)
            ├── PostgreSQL(外部)
            ├── Redis(外部)
            ├── Elasticsearch(外部)
            ├── MinIO(外部)
            └── AI服务(kb-ai:8001)
                    ├── Redis(外部)
                    ├── Elasticsearch(外部)
                    ├── ONNX模型(volume挂载)
                    └── Ollama(外部:11434)
```
