# ES Shard 优化离线执行手册

## 目标

本文档用于离线环境评估并迁移 `kb_document_*`、`kb_doc_meta_*`、`kb_doc_search_*`、`kb_qa_pairs_*` 的 shards/replicas 配置，目标是避免亿级 chunks 下出现单 shard 过大、模板冲突、权限过滤后召回不稳定的问题。

## 第一性原则

ES 能否支撑亿级 chunks，核心不是“shards 越多越好”，而是让每个 primary shard 的数据量、segment 数、向量图构建成本、查询并发度处在可控范围内。

当前已知基准：

- `kb_document_official`：约 `1500 万 chunks`
- primary store：约 `300GB`
- 平均每 chunk 存储成本：约 `20KB`

按此线性估算：

- `1 亿 chunks` primary store 约 `2TB`
- `replica=1` 后约 `4TB`
- 加上 merge 临时空间、快照和磁盘水位，建议准备 `6TB+` 可用空间

## 阶段 1：离线审计

在离线 ES 环境执行：

```powershell
$env:ES_URL="http://localhost:9200"
$env:INDEX_PATTERN="kb_document*"
$env:SAMPLE_SIZE="1000"
python scripts/es_capacity_audit.py
```

如有认证：

```powershell
$env:ES_USER="elastic"
$env:ES_PASSWORD="your-password"
python scripts/es_capacity_audit.py
```

输出文件：

- `reports/es_capacity/es_capacity_report.json`
- `reports/es_capacity/es_capacity_report.md`
- `reports/es_capacity/es_indices.csv`
- `reports/es_capacity/es_shards.csv`

重点检查：

- `cluster_health.status`
- `number_of_data_nodes`
- `unassigned_shards`
- 每个 index 的 `docs.count`
- 每个 index 的 `pri.store.size`
- 每个 primary shard 的最大/平均 store
- `dense_vector.dims`
- `acl_tokens`、`source_index`、`visible_unit_codes`、`permission_version` 是否存在
- `_source` 样本 p95/p99 大小

## 阶段 2：shard 初始建议

以 `kb_document_official = 1500万 / 300GB` 为基准：

| 场景 | 建议 primary shards | 说明 |
|---|---:|---|
| 当前 `kb_document_official` | `8 - 12` | 单 shard 约 `25GB - 38GB` |
| 5000 万 chunks | `16 - 32` | 取决于节点数和 QPS |
| 1 亿 chunks | `32 - 64` | 避免 fine chunks 单 shard 过大 |
| doc_meta/doc_vector | `3 - 12` | 文档级数据通常小于 chunk 级 |
| doc_search | `3 - 6` | 关键词辅助索引，以 BM25/过滤为主 |
| qa_pairs | `3 - 6` | 视 QA 规模和向量查询 QPS 调整 |

生产环境建议：

```powershell
$env:KB_DOCUMENT_REPLICAS="1"
$env:KB_DOC_META_REPLICAS="1"
$env:KB_DOC_SEARCH_REPLICAS="1"
$env:KB_QA_REPLICAS="1"
```

离线单节点演练如必须使用 `0 replica`：

```powershell
$env:ALLOW_SINGLE_NODE_ES="true"
```

## 阶段 3：配置模板

示例，针对 `kb_document_official_v3` 迁移：

```powershell
$env:APP_ENV="prod"
$env:KB_DOCUMENT_SHARDS="12"
$env:KB_DOCUMENT_REPLICAS="1"
$env:KB_DOC_META_SHARDS="6"
$env:KB_DOC_META_REPLICAS="1"
$env:KB_DOC_SEARCH_SHARDS="4"
$env:KB_DOC_SEARCH_REPLICAS="1"
$env:KB_QA_SHARDS="4"
$env:KB_QA_REPLICAS="1"
```

如果是单节点离线验证：

```powershell
$env:APP_ENV="prod"
$env:ALLOW_SINGLE_NODE_ES="true"
$env:KB_DOCUMENT_SHARDS="12"
$env:KB_DOCUMENT_REPLICAS="0"
```

注意：primary shard 数不能原地修改。已有 v2 索引必须新建 v3 后迁移。

## Docker 离线部署执行方式

结论：本手册支持 Docker 部署的 AI 服务离线迁移，但执行时必须在容器环境中显式注入 ES 地址、shard 配置和单节点保护开关。不要直接照搬宿主机的 `localhost:9200`，容器内的 `localhost` 指向 AI 容器自身。

### 适用部署形态

当前项目里常见三种 Docker 形态：

| 部署文件 | AI 容器 | ES 地址写法 | 说明 |
|---|---|---|---|
| `ai_service/docker-compose.yml` | `ai-service` | `http://192.168.74.1:9200` 或宿主机实际 IP | AI 单独容器，ES/Redis/Java 多在宿主机或外部 |
| `docker-compose-offline-ab-optimized.yml` | `kb-ai-embedding` / `kb-ai-worker-qa` | `http://${JAVA_SERVER_IP}:9200` | A/B 离线部署，AI 和 Java/ES 可能分机 |
| `docker-compose-offline.yml` | AI 服务默认被注释 | `http://elasticsearch:9200` | 如果启用同 compose 网络内 ES，可用服务名 |

### 容器内跑容量审计

单 AI 容器部署：

```bash
docker exec -it ai-service bash
cd /app
export ES_URL="http://192.168.74.1:9200"
export ES_USER="admin"
export ES_PASSWORD="your-password"
export INDEX_PATTERN="kb_document*"
export SAMPLE_SIZE="1000"
python scripts/es_capacity_audit.py
```

A/B 部署时在任一带 AI 镜像的容器执行：

```bash
docker exec -it kb-ai-worker-qa bash
cd /app
export ES_URL="${ES_HOST}"
export INDEX_PATTERN="kb_document*"
export SAMPLE_SIZE="1000"
python scripts/es_capacity_audit.py
```

如果不希望进入容器，可直接执行：

```bash
docker exec ai-service bash -lc 'cd /app && ES_URL=http://192.168.74.1:9200 INDEX_PATTERN=kb_document* SAMPLE_SIZE=1000 python scripts/es_capacity_audit.py'
```

### Docker 环境变量建议

生产多节点：

```bash
export APP_ENV=prod
export KB_DOCUMENT_SHARDS=12
export KB_DOCUMENT_REPLICAS=1
export KB_DOC_META_SHARDS=6
export KB_DOC_META_REPLICAS=1
export KB_DOC_SEARCH_SHARDS=4
export KB_DOC_SEARCH_REPLICAS=1
export KB_QA_SHARDS=4
export KB_QA_REPLICAS=1
```

离线单节点演练：

```bash
export APP_ENV=prod
export ALLOW_SINGLE_NODE_ES=true
export KB_DOCUMENT_SHARDS=12
export KB_DOCUMENT_REPLICAS=0
export KB_DOC_META_SHARDS=3
export KB_DOC_META_REPLICAS=0
export KB_DOC_SEARCH_SHARDS=3
export KB_DOC_SEARCH_REPLICAS=0
export KB_QA_SHARDS=3
export KB_QA_REPLICAS=0
```

如果使用 `docker compose`，建议把这些变量写入同目录 `.env`，或写入 `environment:`，避免只在交互 shell 中临时生效。

### 迁移前必须暂停写入

Docker 部署下，迁移 v3 前建议暂停 AI worker，避免 reindex 和新写入并发造成 v2/v3 差异。

单 AI 容器：

```bash
docker stop ai-service
```

A/B 部署只停 worker，不停在线检索：

```bash
docker stop kb-ai-worker-qa
```

如果必须不停机，需要实现双写或冻结上传入口；否则 alias 切换前会产生差量补偿成本。

### 容器内创建/迁移 v3

当前手册中的 `_reindex` 示例可以通过 Kibana、curl 或容器内 Python/HTTP 脚本执行。容器方式示例：

```bash
docker exec -it ai-service bash
cd /app
export ES_HOST="http://192.168.74.1:9200"
export KB_DOCUMENT_SHARDS=12
export KB_DOCUMENT_REPLICAS=0
```

然后按本手册后续 v3 创建、`_reindex`、count 校验、alias cutover 步骤执行。

注意：`ES_SETUP_MODE=init` 只会影响新建索引或模板注册，不会改变已有 v2 primary shard 数。v2 到 v3 仍必须走新索引 + reindex + alias 切换。

## 阶段 4：v3 索引迁移

推荐流程：

1. 创建新物理索引，例如 `kb_document_official_v3`
2. 使用新 settings/mapping
3. 从 v2 reindex 到 v3
4. 校验 docs count、mapping、向量维度、权限字段
5. 灰度读 alias
6. 切换 write alias
7. 观察稳定后保留 v2 一段时间
8. 最后清理旧索引

示例 reindex：

```json
POST _reindex?wait_for_completion=false
{
  "source": {
    "index": "kb_document_official_v2"
  },
  "dest": {
    "index": "kb_document_official_v3"
  }
}
```

检查任务：

```json
GET _tasks/<task_id>
```

校验数量：

```json
GET kb_document_official_v2/_count
GET kb_document_official_v3/_count
```

## 阶段 5：alias 切换

切换前确认：

- v3 docs count 与 v2 一致
- v3 mapping 中权限字段存在
- v3 vector dims 与模型输出一致
- 抽样 query 结果正常
- 业务写入已暂停或进入双写窗口

alias 切换示例：

```json
POST _aliases
{
  "actions": [
    { "remove": { "index": "kb_document_official_v2", "alias": "kb_document_official_write" } },
    { "add": { "index": "kb_document_official_v3", "alias": "kb_document_official_write", "is_write_index": true } }
  ]
}
```

读 alias 可先灰度双挂，再按业务策略收敛。

## 阶段 6：回滚方案

回滚只切 alias，不删除 v3：

```json
POST _aliases
{
  "actions": [
    { "remove": { "index": "kb_document_official_v3", "alias": "kb_document_official_write" } },
    { "add": { "index": "kb_document_official_v2", "alias": "kb_document_official_write", "is_write_index": true } }
  ]
}
```

回滚后检查：

- 新写入是否回到 v2
- 查询结果是否恢复
- v3 是否需要重新 reindex 差量

## 阶段 7：检索优化配套

亿级 chunks 下不建议直接对全量 fine chunks 做一次性 KNN。推荐：

1. ACL/tenant/source filter 前置
2. doc_search 或 doc_vector 先召回候选文档
3. coarse chunk 召回候选段落
4. fine chunk 只在候选集合内召回
5. reranker 做最终排序

观察指标：

- P50/P95/P99 search latency
- rejected search/write
- JVM heap
- segment count
- merge time
- disk watermark
- unassigned shards
- post-filter denied count
- 每次 query 的候选 doc/chunk 数

## 阶段 8：是否切向量数据库

先用上述方案压测 ES。满足以下任一条件时，再考虑拆向量层：

- ES vector P99 无法达标
- HNSW 内存或构建时间不可接受
- filter + ANN 的召回质量不稳定
- 单独扩容向量查询资源比扩容整个 ES 集群更经济

即使拆出向量库，也建议保留 ES 承担：

- BM25
- 权限过滤
- metadata 查询
- 审计和回溯
