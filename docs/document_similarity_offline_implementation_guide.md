# 文档相似度推荐离线环境实施手册

## 1. 文档目的

本文用于指导在离线环境中落地文档相似度推荐优化方案。目标是将文档相似推荐从可能退化到 `kb_document_*` chunk 级检索，改为稳定走文档级向量索引 `kb_doc_meta_v2`，从而在 500 万 chunk 数据规模下避免大范围扫描和高耗时。

适用环境：

- 离线服务器：P4 GPU 2 张，每张 8GB 显存。
- 数据规模：约 500 万 chunk 分片。
- Elasticsearch：已有 `kb_document_*` 主文档 chunk 索引。
- Java 服务：提供编辑器文档相似推荐接口。
- AI 服务：提供 `/api/ai/vector/long-doc` 和可选 rerank 接口。

## 2. 实施目标

本次实施完成后，系统应达到以下状态：

1. 新建文档级索引 `kb_doc_meta_v2`。
2. `kb_doc_meta_read` 和 `kb_doc_meta_write` 别名均指向 `kb_doc_meta_v2`。
3. 历史数据从 `kb_document_*` 回填到 `kb_doc_meta_v2`，一篇文档一条 latest doc_meta。
4. 新入库文档持续写入 `kb_doc_meta_write`。
5. 文档相似推荐查询读 `kb_doc_meta_read`，并在 KNN 阶段前置过滤 `is_latest` 和 `acl_tokens`。
6. 普通检索、问答检索、chunk 证据召回仍走 `kb_document_*`，不受 `kb_doc_meta_v2` 影响。
7. `fallback_used=true` 只能作为异常兜底，不应成为常态。

## 3. 代码变更范围

当前代码中与实施相关的核心文件：

| 文件 | 作用 |
|---|---|
| `ai_service/core/indexing/es_setup.py` | 创建 `kb_doc_meta_v2` mapping，维护 read/write 别名 |
| `ai_service/core/indexing/doc_indexer.py` | 新文档入库后写 `kb_doc_meta_write` |
| `ai_service/core/indexing/dedup_checker.py` | 去重兜底查 `kb_doc_meta_read` |
| `ai_service/scripts/backfill_doc_meta_v2.py` | 历史数据回填脚本 |
| `ai_service/scripts/validate_doc_meta_v2.py` | 回填后验收脚本 |
| `ai_service/scripts/batch_doc_vector_generator.py` | 旧脚本兼容入口，已委托 v2 回填脚本 |
| `java_service/src/main/java/com/boyang/search/service/SimilarityService.java` | 文档相似推荐读 `kb_doc_meta_read`，前置 ACL 过滤 |
| `java_service/src/main/resources/application.yml` | 相似推荐索引、窗口、rerank 配置 |

## 4. 实施前置检查

### 4.1 检查服务与数据状态

确认 Elasticsearch 可访问：

```powershell
curl http://localhost:9200
```

确认主文档索引存在：

```powershell
curl http://localhost:9200/_cat/indices/kb_document*?v
```

确认当前是否已有旧 `kb_doc_meta`：

```powershell
curl http://localhost:9200/_cat/indices/kb_doc_meta*?v
curl http://localhost:9200/_cat/aliases/kb_doc_meta*?v
```

### 4.2 检查磁盘空间

`kb_doc_meta_v2` 是文档级索引，数据量远小于 500 万 chunk，但 dense vector 会占用额外空间。建议预留：

```text
文档数 * 1024 * 4 bytes * HNSW/索引开销
```

经验上应至少预留数 GB 到数十 GB，具体取决于文档数量。

检查磁盘：

```powershell
Get-PSDrive
```

或 Linux：

```bash
df -h
```

### 4.3 备份旧索引与别名信息

导出当前 mapping：

```powershell
curl http://localhost:9200/kb_doc_meta/_mapping > kb_doc_meta_mapping_backup.json
curl http://localhost:9200/_alias/kb_doc_meta_read > kb_doc_meta_read_alias_backup.json
curl http://localhost:9200/_alias/kb_doc_meta_write > kb_doc_meta_write_alias_backup.json
```

如果当前没有别名，上述 alias 命令可能返回 404，这是正常情况。

### 4.4 确认代码版本

在代码目录执行：

```powershell
cd E:\project\AI\knowledge-base
git status --short
```

确认以下文件已经包含本次优化：

```powershell
rg -n "kb_doc_meta_v2|kb_doc_meta_read|kb_doc_meta_write" ai_service java_service
```

## 5. 环境变量配置

推荐在离线环境启动脚本或 `.env` 中明确配置：

```powershell
$env:ES_HOST="http://localhost:9200"
$env:SOURCE_INDEX="kb_document_*"
$env:KB_DOC_META_INDEX="kb_doc_meta_v2"
$env:KB_DOC_META_READ_ALIAS="kb_doc_meta_read"
$env:KB_DOC_META_WRITE_ALIAS="kb_doc_meta_write"
$env:DOC_META_BACKFILL_BATCH_SIZE="200"
$env:DOC_VECTOR_DIM="1024"
$env:KB_DOC_META_DEFAULT_ACL_TOKENS="_INTERNAL"
```

如果 ES 开启认证：

```powershell
$env:ES_USER="elastic"
$env:ES_PASS="你的密码"
```

Java 相似推荐配置：

```powershell
$env:EDITOR_SIMILARITY_META_INDEX="kb_doc_meta_read"
$env:EDITOR_SIMILARITY_META_WRITE_INDEX="kb_doc_meta_write"
$env:EDITOR_SIMILARITY_CHUNK_FALLBACK_INDEX="kb_document"
$env:EDITOR_SIMILARITY_MAX_CANDIDATES="20"
$env:EDITOR_SIMILARITY_KNN_NUM_CANDIDATES="120"
$env:EDITOR_SIMILARITY_RERANK_ENABLED="true"
```

如需优先追求毫秒级返回，可临时关闭 rerank：

```powershell
$env:EDITOR_SIMILARITY_RERANK_ENABLED="false"
```

## 6. 实施步骤

### 6.0 Docker 部署场景说明

如果离线环境的 AI 服务使用 Docker 部署，迁移脚本建议在 AI 服务镜像或同镜像的一次性容器内执行，而不是在宿主机直接执行。原因：

1. 容器内已经有与 AI 服务一致的 Python 依赖。
2. 容器内 `/app/scripts`、`/app/core` 路径与代码导入一致。
3. 容器网络中可直接访问 `elasticsearch`、`kb-java` 等服务名。
4. 避免宿主机没有 Python 依赖、ES 地址写错、离线包缺失等问题。

当前常见容器名称：

| compose 文件 | service 名 | container_name |
|---|---|---|
| `docker-compose-prod.yml` | `ai-service` | `kb-ai` |
| `ai_service/docker-compose.yml` | `knowledge-base-ai` | `ai-service` |

以下命令中如果使用 `docker exec`，请按现场实际容器名替换 `kb-ai` 或 `ai-service`。

#### 6.0.1 现场镜像必须包含新脚本

本次迁移依赖以下脚本已经存在于镜像 `/app/scripts`：

```text
/app/scripts/es_init.py
/app/scripts/backfill_doc_meta_v2.py
/app/scripts/validate_doc_meta_v2.py
/app/scripts/batch_doc_vector_generator.py
```

先检查镜像内是否已有脚本：

```bash
docker exec -it kb-ai ls -l /app/scripts/backfill_doc_meta_v2.py /app/scripts/validate_doc_meta_v2.py
```

如果不存在，说明现场仍是旧镜像，必须先升级 AI 镜像，不能直接迁移。

#### 6.0.2 离线镜像升级方式

在有代码和构建条件的机器上构建新镜像：

```bash
cd /path/to/knowledge-base/ai_service
docker build -t knowledge-base-ai:1.0.1 -f Dockerfile .
docker save -o knowledge-base-ai-1.0.1.tar knowledge-base-ai:1.0.1
```

将 `knowledge-base-ai-1.0.1.tar` 拷贝到离线服务器后加载：

```bash
docker load -i knowledge-base-ai-1.0.1.tar
```

修改 compose 中 AI 服务镜像：

```yaml
ai-service:
  image: knowledge-base-ai:1.0.1
```

重启 AI 服务：

```bash
docker compose -f docker-compose-prod.yml up -d ai-service
```

如果现场使用的是 `ai_service/docker-compose.yml`：

```bash
docker compose -f ai_service/docker-compose.yml up -d knowledge-base-ai
```

#### 6.0.3 Docker 环境变量配置

推荐将迁移相关变量写入 compose 的 AI 服务 `environment` 中，或写入 `/app/config/.env` 对应的宿主机文件，例如 `./ai_service/config/.env`。

必须配置：

```env
ES_HOST=http://elasticsearch:9200
SOURCE_INDEX=kb_document_*
KB_DOC_META_INDEX=kb_doc_meta_v2
KB_DOC_META_READ_ALIAS=kb_doc_meta_read
KB_DOC_META_WRITE_ALIAS=kb_doc_meta_write
KB_DOC_META_DEFAULT_ACL_TOKENS=_INTERNAL
DOC_META_BACKFILL_BATCH_SIZE=200
DOC_VECTOR_DIM=1024
```

如果 ES 不在同一个 compose 网络中，`ES_HOST` 应改为宿主机或 ES 服务器 IP：

```env
ES_HOST=http://192.168.x.x:9200
```

如果 ES 开启认证：

```env
ES_USER=elastic
ES_PASSWORD=你的密码
ES_PASS=你的密码
```

注意：`es_init.py` 读取 `ES_PASSWORD`，回填和验收脚本兼容 `ES_PASS/ES_PASSWORD`。为了避免混淆，建议两个都配置。

#### 6.0.4 推荐的一次性容器执行方式

如果使用 `docker compose run`，脚本在一次性容器中执行，不影响正在运行的 AI 服务：

```bash
docker compose -f docker-compose-prod.yml run --rm --no-deps \
  -e ES_SETUP_MODE=init \
  -e ES_HOST=http://elasticsearch:9200 \
  -e SOURCE_INDEX=kb_document_* \
  -e KB_DOC_META_INDEX=kb_doc_meta_v2 \
  -e KB_DOC_META_READ_ALIAS=kb_doc_meta_read \
  -e KB_DOC_META_WRITE_ALIAS=kb_doc_meta_write \
  -e KB_DOC_META_DEFAULT_ACL_TOKENS=_INTERNAL \
  ai-service python scripts/es_init.py
```

回填：

```bash
docker compose -f docker-compose-prod.yml run --rm --no-deps \
  -e ES_HOST=http://elasticsearch:9200 \
  -e SOURCE_INDEX=kb_document_* \
  -e KB_DOC_META_INDEX=kb_doc_meta_v2 \
  -e KB_DOC_META_READ_ALIAS=kb_doc_meta_read \
  -e KB_DOC_META_WRITE_ALIAS=kb_doc_meta_write \
  -e KB_DOC_META_DEFAULT_ACL_TOKENS=_INTERNAL \
  -e DOC_META_BACKFILL_BATCH_SIZE=200 \
  ai-service python scripts/backfill_doc_meta_v2.py
```

验收：

```bash
docker compose -f docker-compose-prod.yml run --rm --no-deps \
  -e ES_HOST=http://elasticsearch:9200 \
  -e SOURCE_INDEX=kb_document_* \
  -e KB_DOC_META_INDEX=kb_doc_meta_v2 \
  -e KB_DOC_META_READ_ALIAS=kb_doc_meta_read \
  -e KB_DOC_META_WRITE_ALIAS=kb_doc_meta_write \
  ai-service python scripts/validate_doc_meta_v2.py
```

如果现场使用 `ai_service/docker-compose.yml`，service 名应替换为 `knowledge-base-ai`：

```bash
docker compose -f ai_service/docker-compose.yml run --rm --no-deps \
  knowledge-base-ai python scripts/backfill_doc_meta_v2.py
```

#### 6.0.5 直接进入运行中容器执行

也可以直接进入运行中的 AI 容器执行：

```bash
docker exec -it kb-ai bash
cd /app
export ES_HOST=http://elasticsearch:9200
export SOURCE_INDEX=kb_document_*
export KB_DOC_META_INDEX=kb_doc_meta_v2
export KB_DOC_META_READ_ALIAS=kb_doc_meta_read
export KB_DOC_META_WRITE_ALIAS=kb_doc_meta_write
export KB_DOC_META_DEFAULT_ACL_TOKENS=_INTERNAL
export DOC_META_BACKFILL_BATCH_SIZE=200

ES_SETUP_MODE=init python scripts/es_init.py
python scripts/backfill_doc_meta_v2.py
python scripts/validate_doc_meta_v2.py
```

长时间回填建议不要依赖交互终端，可以用后台方式：

```bash
docker exec -d kb-ai sh -c 'cd /app && ES_HOST=http://elasticsearch:9200 SOURCE_INDEX=kb_document_* KB_DOC_META_INDEX=kb_doc_meta_v2 KB_DOC_META_READ_ALIAS=kb_doc_meta_read KB_DOC_META_WRITE_ALIAS=kb_doc_meta_write KB_DOC_META_DEFAULT_ACL_TOKENS=_INTERNAL DOC_META_BACKFILL_BATCH_SIZE=200 python scripts/backfill_doc_meta_v2.py > /tmp/backfill_doc_meta_v2.log 2>&1'
docker exec -it kb-ai tail -f /tmp/backfill_doc_meta_v2.log
```

#### 6.0.6 Docker 场景迁移总流程

Docker 离线环境推荐流程：

```text
1. 构建并导入包含新脚本的新 AI 镜像
2. 修改 compose，补齐 KB_DOC_META_* 和 EDITOR_SIMILARITY_* 配置
3. 重启 AI 服务，确认脚本存在
4. 暂停新文档入库任务
5. 在一次性 AI 容器中执行 es_init.py
6. 在一次性 AI 容器中执行 backfill_doc_meta_v2.py
7. 在一次性 AI 容器中执行 validate_doc_meta_v2.py
8. 重启 Java 服务，使相似推荐配置生效
9. 调用编辑器相似推荐接口，确认 fallback_used=false
10. 恢复新文档入库任务
```

#### 6.0.7 Docker 场景注意事项

- 回填脚本不需要 GPU，瓶颈主要在 ES、CPU 和磁盘。
- 不建议在正在承载高并发推理的 AI 容器中交互式执行长时间回填，优先使用 `docker compose run --rm` 一次性容器。
- 如果 compose 中只挂载了 `/app/config` 和模型目录，而没有挂载源码，则必须升级镜像，不能只替换宿主机脚本。
- 如果容器内 `ES_HOST=http://localhost:9200`，通常是错误的；容器内 localhost 指向容器自身。应使用 `http://elasticsearch:9200` 或 ES 服务器 IP。
- 回填完成前不要切普通检索链路。普通检索不应使用 `kb_doc_meta_v2`。

### 6.1 停止自动入库任务

实施期间建议暂停以下入口，避免回填期间数据持续变化：

- Kafka 文档入库消费。
- SFTP/URL/数据库同步任务。
- 手工批量导入任务。
- XXL Job 文档同步任务。

如果无法停机，需要记录实施开始时间，并在回填完成后补跑增量。

### 6.2 初始化 `kb_doc_meta_v2` 和别名

方式一：通过 AI 服务初始化脚本：

```powershell
cd E:\project\AI\knowledge-base
$env:ES_SETUP_MODE="init"
python ai_service/scripts/es_init.py
```

方式二：如果不使用 `es_init.py`，可直接运行回填脚本。回填脚本会先确保索引和别名存在：

```powershell
python ai_service/scripts/backfill_doc_meta_v2.py
```

初始化后检查：

```powershell
curl http://localhost:9200/kb_doc_meta_v2/_mapping
curl http://localhost:9200/_cat/aliases/kb_doc_meta*?v
```

期望：

```text
kb_doc_meta_read  -> kb_doc_meta_v2
kb_doc_meta_write -> kb_doc_meta_v2, is_write_index=true
doc_vector.type   -> dense_vector
doc_vector.dims   -> 1024
```

### 6.3 执行历史数据回填

执行：

```powershell
cd E:\project\AI\knowledge-base
python ai_service/scripts/backfill_doc_meta_v2.py
```

脚本行为：

1. 扫描 `SOURCE_INDEX`，默认 `kb_document_*`。
2. 只取 `chunk_granularity=fine` 的 chunk。
3. 只取 latest chunk，兼容缺失 `metadata.is_latest` 的历史数据。
4. 按 `metadata.doc_id -> content_hash -> source` 聚合。
5. 对 fine chunk vector 做 mean pooling + L2 normalize。
6. 写入 `kb_doc_meta_write`。
7. 最后 refresh `kb_doc_meta_v2`。

回填期间不要使用旧脚本直接写 `kb_doc_meta`。旧入口 `batch_doc_vector_generator.py` 当前已变为兼容包装器，会委托新回填脚本。

### 6.4 回填耗时预估

500 万 chunk 回填不重新跑 embedding，只复用已有 chunk vector。主要耗时在：

- ES 扫描读取 500 万条 fine chunk。
- Java/Python 侧累加 1024 维向量。
- 写入文档级 doc_meta。
- ES HNSW dense_vector 索引构建。

在单节点离线环境，实际耗时通常取决于 CPU、磁盘和 ES JVM 堆，而不是 P4 GPU。GPU 在回填阶段基本不参与。

粗略预估：

| 场景 | 预估耗时 |
|---|---:|
| 文档数较少、磁盘较快 | 30 分钟到 2 小时 |
| 500 万 chunk、普通 SATA/机械盘 | 2 到 6 小时 |
| ES 压力大或 JVM 堆较小 | 可能更久 |

如果回填期间 ES 查询变慢，应降低批大小：

```powershell
$env:DOC_META_BACKFILL_BATCH_SIZE="100"
python ai_service/scripts/backfill_doc_meta_v2.py
```

### 6.5 执行回填验收

执行：

```powershell
python ai_service/scripts/validate_doc_meta_v2.py
```

验收脚本会检查：

- `doc_vector` mapping 是否为 `dense_vector`。
- `doc_vector.dims` 是否为 1024。
- `kb_doc_meta_read` 是否存在。
- `kb_doc_meta_write` 是否存在且 `is_write_index=true`。
- latest doc_meta 是否为空。
- latest doc_meta 是否缺失 `doc_vector`。
- latest doc_meta 是否缺失 `acl_tokens`。
- 抽样检查向量维度。
- 检查重复 latest `doc_id`。
- 对比源索引 distinct 文档数和 doc_meta latest 数。

验收通过标准：

```text
failures = []
```

如果只有 warnings，需要人工判断。常见 warning 是历史数据缺少 `metadata.doc_id`，导致按 `source_name` 对比时有差异。

### 6.6 启动或重启服务

启动 AI 服务：

```powershell
cd E:\project\AI\knowledge-base\ai_service
python main.py
```

启动 Java 服务：

```powershell
cd E:\project\AI\knowledge-base\java_service
$env:MAVEN_OPTS="-Xmx512m -XX:CICompilerCount=2 -XX:+UseSerialGC"
mvn -q -DskipTests spring-boot:run
```

如果服务用 Docker 或离线部署脚本启动，应把第 5 节环境变量写入对应启动配置。

### 6.7 端到端接口验证

调用编辑器相似推荐接口。具体路径以当前控制器为准，重点检查返回的 `timings`：

期望：

```json
{
  "fallback_used": false,
  "meta_index": "kb_doc_meta_read",
  "candidate_count": 20,
  "denied_count": 0
}
```

如果 `fallback_used=true`，说明文档级 KNN 失败或 `kb_doc_meta_read` 不可用，需要立即排查。

### 6.8 性能压测

建议至少压测三类请求：

1. 短文本，刚达到推荐阈值。
2. 普通编辑器草稿，1000 到 4000 字。
3. 大文档摘要文本，接近 `EDITOR_SIMILARITY_MAX_INPUT_CHARS`。

记录：

```text
vector_ms
knn_ms
permission_ms
rerank_ms
costMs
fallback_used
candidate_count
accessible_candidate_count
```

目标：

| 模式 | 目标耗时 |
|---|---:|
| 已有 doc_vector 的文档找相似 | 100ms 到 500ms |
| 编辑器实时文本，关闭 rerank | 300ms 到 1s |
| 编辑器实时文本，top10 rerank | 1s 到 2s |
| 编辑器实时文本，top20 rerank | 1.5s 到 3s |

如果业务强要求毫秒级：

- 关闭同步 rerank。
- 缓存草稿向量。
- 只在用户停顿或手动触发时计算。
- 已入库文档直接复用 `doc_vector`，不要重新跑 `/vector/long-doc`。

## 7. 上线后监控项

必须关注：

```text
fallback_used=true 的比例
knn_ms P95/P99
vector_ms P95/P99
rerank_ms P95/P99
denied_count 是否长期大于 0
latest doc_meta 数量是否持续接近 latest 文档数
缺失 acl_tokens 数量
重复 latest doc_id 数量
```

建议每天或每小时执行：

```powershell
python ai_service/scripts/validate_doc_meta_v2.py
```

## 8. 常见问题处理

### 8.1 `doc_vector` 不是 dense_vector

原因：

- 旧索引 `kb_doc_meta` 被误用。
- `kb_doc_meta_v2` 已提前由动态 mapping 创建成普通数组。

处理：

1. 停止写入。
2. 删除错误的 `kb_doc_meta_v2`，或新建 `kb_doc_meta_v3`。
3. 用正确 mapping 重新创建。
4. 切换 `kb_doc_meta_read/write`。
5. 重新回填。

### 8.2 回填很慢

优先检查：

```powershell
curl http://localhost:9200/_cat/thread_pool/search?v
curl http://localhost:9200/_cat/nodes?v
curl http://localhost:9200/_cat/indices/kb_doc_meta_v2?v
```

优化手段：

- 降低 `DOC_META_BACKFILL_BATCH_SIZE`。
- 在业务低峰运行。
- 确保 ES JVM heap 足够。
- 确保磁盘没有打满。

### 8.3 推荐接口仍然 2 到 6 秒

先看 timings：

- `vector_ms` 高：瓶颈在实时 embedding，P4 8G 上很常见。
- `rerank_ms` 高：瓶颈在 rerank，可关闭同步 rerank。
- `knn_ms` 高：检查 `kb_doc_meta_read` 是否误指多索引或旧索引。
- `fallback_used=true`：文档级索引不可用，正在退化到 chunk fallback。

临时降耗：

```powershell
$env:EDITOR_SIMILARITY_RERANK_ENABLED="false"
$env:EDITOR_SIMILARITY_MAX_CANDIDATES="10"
$env:EDITOR_SIMILARITY_KNN_NUM_CANDIDATES="80"
```

### 8.4 普通检索是否受影响

正常不受影响。普通检索仍走：

```text
kb_document_* / kb_document / 分区索引
```

文档相似推荐走：

```text
kb_doc_meta_read
```

如果普通检索结果变化，应优先排查是否误改了 `kb_document` 别名或主检索 pipeline。

## 9. 回滚方案

### 9.1 快速回滚推荐链路

如果新索引存在问题，可以将别名切回旧索引：

```powershell
curl -X POST http://localhost:9200/_aliases -H "Content-Type: application/json" -d "{\"actions\":[{\"remove\":{\"index\":\"kb_doc_meta_v2\",\"alias\":\"kb_doc_meta_read\"}},{\"remove\":{\"index\":\"kb_doc_meta_v2\",\"alias\":\"kb_doc_meta_write\"}},{\"add\":{\"index\":\"kb_doc_meta\",\"alias\":\"kb_doc_meta_read\"}},{\"add\":{\"index\":\"kb_doc_meta\",\"alias\":\"kb_doc_meta_write\",\"is_write_index\":true}}]}"
```

注意：如果旧 `kb_doc_meta.doc_vector` mapping 不正确，切回旧索引只能作为临时止血，不能保证相似推荐正常。

### 9.2 关闭 rerank 降级

```powershell
$env:EDITOR_SIMILARITY_RERANK_ENABLED="false"
```

### 9.3 暂停文档相似推荐入口

如果推荐接口影响主服务稳定，可以在网关或前端临时关闭编辑器自动推荐，仅保留普通检索。

## 10. 最终验收清单

上线前必须全部通过：

- [ ] `kb_doc_meta_v2` 存在。
- [ ] `doc_vector.type=dense_vector`。
- [ ] `doc_vector.dims=1024`。
- [ ] `kb_doc_meta_read -> kb_doc_meta_v2`。
- [ ] `kb_doc_meta_write -> kb_doc_meta_v2` 且 `is_write_index=true`。
- [ ] `validate_doc_meta_v2.py` 输出 `failures=[]`。
- [ ] latest doc_meta 数量与 latest 文档数量差异在可解释范围内。
- [ ] 抽样文档 ACL 与 MySQL/业务权限一致。
- [ ] 编辑器相似推荐返回 `fallback_used=false`。
- [ ] `knn_ms` 在可接受范围内。
- [ ] 关闭 rerank 后接口耗时明显下降。
- [ ] 普通检索结果不受影响。
- [ ] 回滚命令已验证可执行。

## 11. 推荐执行顺序摘要

```powershell
cd E:\project\AI\knowledge-base

# 1. 配置环境变量
$env:ES_HOST="http://localhost:9200"
$env:SOURCE_INDEX="kb_document_*"
$env:KB_DOC_META_INDEX="kb_doc_meta_v2"
$env:KB_DOC_META_READ_ALIAS="kb_doc_meta_read"
$env:KB_DOC_META_WRITE_ALIAS="kb_doc_meta_write"
$env:KB_DOC_META_DEFAULT_ACL_TOKENS="_INTERNAL"
$env:DOC_META_BACKFILL_BATCH_SIZE="200"

# 2. 初始化索引和别名
$env:ES_SETUP_MODE="init"
python ai_service/scripts/es_init.py

# 3. 回填历史数据
python ai_service/scripts/backfill_doc_meta_v2.py

# 4. 验收
python ai_service/scripts/validate_doc_meta_v2.py

# 5. 启动 Java/AI 服务后做接口验证
```

## 12. 结论

在离线 P4 双 8G 环境下，本次优化的关键不是提升 GPU 算力，而是让文档相似推荐避开 500 万 chunk 的主索引路径，稳定走文档级 `kb_doc_meta_v2`。只要回填和别名切换正确，文档级 KNN 应保持在毫秒到数百毫秒级；如果接口仍然达到秒级，优先排查实时 embedding、rerank 和 fallback，而不是 ES 文档级 KNN 本身。
