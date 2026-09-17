# 1亿+ Chunk 生产级检索优化需求文档

## 1. 文档信息

| 项目 | 内容 |
| --- | --- |
| 文档名称 | 1亿+ Chunk 生产级检索优化需求文档 |
| 适用系统 | knowledge-base 知识库检索系统 |
| 覆盖接口 | `/api/v1/search`、`/api/v1/search/home`、知识问答、文档相似度 |
| 当前阶段 | Phase 0-3 已完成首轮实现，待真实环境验证 |
| 目标环境 | 生产环境单索引 1亿+ chunk，多索引/多节点可扩展 |

## 2. 背景与问题

当前系统在 `/home` 首页聚合检索中，同时执行关键词检索、混合检索、知识问答候选召回与 LLM 回答链路。在单机 P4、双 8G 显卡、千万级以上 chunk 分片场景下，关键词检索和混合检索出现 5s 以上延迟。

从第一性原理看，根因不是单个 DSL 慢，而是在线链路把“文档发现”和“证据片段检索”混在同一个 1亿级 chunk 倒排/向量索引中完成：

- keyword 模式为了找文档，需要在 chunk 索引上做 BM25、collapse、分页枚举。
- hybrid 模式同时在 chunk 索引上跑 BM25、dense vector KNN、sparse vector 查询。
- `/home` 还会同步执行文档扩展、证据补齐、rerank 和 QA evidence prefetch。
- chunk 粒度越细，索引规模越大，同一文档的多个 chunk 会重复竞争召回窗口。
- 单机 P4/双 8G 显卡无法同时承担 embedding、rerank、LLM、ES 大索引召回的尾延迟压力。

因此生产级优化的核心原则是：在线首阶段只做“文档级候选召回”，把 1亿+ chunk 主索引用于证据回表、全文验证和详情展示。

## 3. 目标

### 3.1 业务目标

- 保证关键词检索、混合检索、知识问答、文档相似度在 1亿+ chunk 数据规模下可生产使用。
- `/home` 首页聚合检索不因 chunk 全库扫描、扩展、rerank、QA 链路叠加而超过 SLA。
- 关键词检索保持精确文号、标题、关键词、章节、正文代表片段的召回能力。
- 混合检索保留语义召回能力，同时通过文档级候选减少 chunk 级召回范围。
- 所有检索结果必须继续满足权限过滤与后置权限兜底。

### 3.2 性能目标

建议生产验收指标如下：

| 场景 | 目标 |
| --- | --- |
| `/api/v1/search` keyword | P95 <= 800ms，P99 <= 1500ms |
| `/api/v1/search/home` hybrid 检索阶段 | P95 <= 2000ms，不含 LLM 生成 |
| 文档级候选召回 | P95 <= 300ms |
| doc_search 候选数 | 常态 <= 500 |
| 权限后置拦截 | `post_filter_denied_count` 长期接近 0 |

## 4. 范围

### 4.1 本次已实施范围

- `/home` hybrid 轻量模式。
- 新增 `kb_doc_search_v1` 文档级检索索引。
- 新增实时入库双写 `kb_doc_search_write`。
- 新增历史数据回填脚本。
- keyword 模式首阶段切换到 `kb_doc_search`。
- `/home` hybrid 增加 `kb_doc_search` 文档候选预过滤。
- 增加灰度开关、审计字段、timings 埋点、压测脚本和运行手册。

### 4.2 暂未完成范围

- 真实 ES + Java 环境接口联调。
- 历史数据完整回填与校验。
- 真实 1亿+ chunk 压测报告。
- 完整两阶段 hybrid：文档级 hybrid 召回 + 精选 chunk 证据回表。
- backfill checkpoint、限速、失败重试和进度持久化。
- ES 多节点、多分片、冷热分层与容量规划落地。

## 5. 总体架构方案

### 5.1 核心索引分层

| 索引 | 粒度 | 用途 |
| --- | --- | --- |
| `kb_doc_search_v1` | 每文档 1 条 | 关键词检索、首页 hybrid 文档候选预过滤 |
| `kb_document_*` | chunk 级 | 全文证据、coarse/fine chunk 回表、向量召回、详情页 |
| `kb_doc_meta_v2` | 每文档 1 条向量 | 文档相似度、文档级语义相似检索 |
| `kb_qa_pairs` | QA pair 级 | 知识问答候选召回 |

### 5.2 检索链路

keyword 模式：

1. 查询 `kb_doc_search`，按文档级字段召回候选文档。
2. 对多关键词执行文档级 AND 交集。
3. 回表 `kb_document_*` 查询 coarse chunk 证据。
4. 排序、组装结果、权限后置兜底。

`/home` hybrid 模式：

1. 轻量模式下先查询 `kb_doc_search`，拿到候选 `source`。
2. BM25/KNN/Sparse chunk 检索增加 `metadata.source terms` 过滤。
3. 跳过同步 DocExpansion。
4. 跳过同步 evidence prefetch。
5. 继续执行 RRF、rerank、权限后置过滤。

入库链路：

1. 原有 chunk 写入 `kb_document_*`。
2. 原有文档向量写入 `kb_doc_meta_write`。
3. 新增文档级检索记录写入 `kb_doc_search_write`。
4. 任一辅助索引写入失败不阻断主 chunk 索引。

## 6. 功能需求

### FR-1 文档级检索索引

系统必须创建并维护 `kb_doc_search_v1` 索引，并通过别名访问：

- 读别名：`kb_doc_search`
- 写别名：`kb_doc_search_write`

索引字段必须覆盖：

- `doc_id`
- `doc_version`
- `content_hash`
- `source`
- `source_name`
- `title`
- `document_number`
- `keywords`
- `tags`
- `entities`
- `section_titles`
- `doc_terms`
- `summary`
- `representative_chunk_ids`
- `doc_type`
- `data_source`
- `is_latest`
- `acl_tokens`
- `visibility`
- `owner_dept_id`
- `publish_time`
- `chunk_count`
- `updated_at`

### FR-2 实时双写

文档入库成功写入 chunk 主索引后，系统必须同步生成文档级检索记录。

双写要求：

- 每个业务文档生成 1 条 `kb_doc_search` 记录。
- 记录必须聚合标题、文号、关键词、标签、章节标题、代表性正文片段和权限字段。
- 新版本写入时，旧版本 `is_latest` 必须置为 `false`。
- `kb_doc_search` 写入失败时，只记录日志，不影响主索引写入。

### FR-3 历史数据迁移

系统必须提供历史 chunk 数据迁移到 `kb_doc_search` 的脚本。

脚本要求：

- 使用 PIT + `search_after` 流式扫描 `kb_document_*`。
- 按稳定文档键聚合 chunk。
- 幂等写入 `kb_doc_search_write`。
- 支持 `SOURCE_INDEX`、`KB_DOC_SEARCH_INDEX`、`DOC_SEARCH_BACKFILL_BATCH_SIZE`、`DRY_RUN` 等环境变量。

当前脚本：

- `ai_service/scripts/backfill_kb_doc_search.py`

### FR-4 keyword 检索接入

keyword 模式必须优先使用 `kb_doc_search` 做文档级候选枚举。

要求：

- 多关键词按文档级命中集合求交集。
- 支持标题、文号、文件名、关键词、标签、实体、章节、代表性正文召回。
- 命中文档后必须回表 chunk 主索引获取 coarse evidence。
- 支持配置回退到旧 chunk collapse 逻辑。

### FR-5 `/home` hybrid 接入

`/home` hybrid 必须支持文档级候选预过滤。

要求：

- 仅在 `homeLightweightMode=true` 时启用。
- 先查 `kb_doc_search` 获取候选 `source`。
- BM25/KNN/Sparse chunk 检索使用 `metadata.source terms` 收窄范围。
- `kb_doc_search` 查询失败或无候选时，必须 fail-open 回退到原 chunk 召回。

### FR-6 `/home` 轻量化

`/home` hybrid 检索必须跳过高成本同步步骤：

- 跳过 DocExpansion。
- 跳过 evidence prefetch。
- 保留必要的召回、融合、rerank 和权限后置过滤。

### FR-7 灰度开关

系统必须支持以下生产配置：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `SEARCH_DOC_SEARCH_ENABLED` | `true` | 文档级检索总开关 |
| `SEARCH_DOC_SEARCH_KEYWORD_ENABLED` | `true` | keyword 是否使用文档级索引 |
| `SEARCH_DOC_SEARCH_HOME_PREFILTER_ENABLED` | `true` | `/home` hybrid 是否启用文档级预过滤 |
| `KB_DOC_SEARCH_READ_ALIAS` | `kb_doc_search` | Java 检索读别名 |
| `SEARCH_DOC_SEARCH_PREFILTER_MAX_CANDIDATES` | `500` | 预过滤最大候选文档数 |

### FR-8 观测与审计

系统必须在 `SearchContext.timings` 中记录：

- `doc_search_enabled`
- `doc_search_keyword_ms`
- `doc_search_keyword_candidates`
- `doc_search_prefilter_ms`
- `doc_search_prefilter_candidates`
- `doc_search_prefilter_applied`
- `doc_expansion_skipped_home`
- `evidence_prefetch_skipped_home`

系统必须在 `search_audit_log` 中记录：

- `doc_search_enabled`
- `doc_search_ms`
- `doc_search_candidates`
- `doc_search_prefilter_applied`

### FR-9 压测工具

系统必须提供轻量级接口压测脚本，用于 A/B 验证优化效果。

当前脚本：

- `ai_service/scripts/search_latency_probe.py`

示例：

```bash
python ai_service/scripts/search_latency_probe.py \
  --url http://localhost:8080 \
  --endpoint /api/v1/search \
  --mode keyword \
  --query "任职 公示" \
  -n 100 -c 10
```

## 7. 非功能需求

### 7.1 可用性

- `kb_doc_search` 故障不得导致主检索不可用。
- keyword 可通过开关回退到旧 chunk 检索。
- `/home` prefilter 可独立关闭。
- 辅助索引双写失败不得阻断入库主流程。

### 7.2 性能

- 生产环境不允许 keyword 默认扫 1亿+ chunk 做文档发现。
- `/home` 不允许同步执行无界文档扩展和证据补齐。
- 文档级候选数必须可配置，默认不超过 500。

### 7.3 安全

- `kb_doc_search` 必须写入 `acl_tokens`、`visibility`、`owner_dept_id`。
- 检索时必须继续执行 ES 权限过滤。
- 最终结果必须继续走 `PermissionGuard` 后置权限校验。
- `post_filter_denied_count > 0` 必须作为权限投影漂移告警信号。

### 7.4 可观测性

- 每次检索必须能区分是否走了 `kb_doc_search`。
- 必须能看到文档级召回耗时、候选数、是否应用预过滤。
- 压测必须输出 P50/P90/P95/P99。

### 7.5 可维护性

- 新增逻辑必须集中在 ES 初始化、入库聚合、检索策略和运行手册中。
- 不允许把 `kb_doc_search` 查询逻辑散落到 Controller 层。
- 新增开关必须能通过环境变量覆盖。

## 8. 已完成代码清单

### Python 服务

- `ai_service/core/indexing/es_setup.py`
  - 新增 `DOC_SEARCH_INDEX`
  - 新增 `DOC_SEARCH_READ_ALIAS`
  - 新增 `DOC_SEARCH_WRITE_ALIAS`
  - 新增 `doc_search_index_mapping()`
  - 新增 `kb_doc_search` 创建和别名注册

- `ai_service/core/indexing/doc_indexer.py`
  - 新增 `update_doc_search()`
  - 入库时聚合文档级字段

- `ai_service/core/rag_pipeline.py`
  - 入库完成后调用 `update_doc_search()`

- `ai_service/scripts/backfill_kb_doc_search.py`
  - 新增历史数据回填脚本

- `ai_service/scripts/search_latency_probe.py`
  - 新增接口压测脚本

### Java 服务

- `SearchController.java`
  - `/home` hybrid 调用首页专用轻量检索入口

- `SearchServiceV2.java`
  - 新增 `hybridSearchContextForHome()`
  - 写入审计指标

- `SearchContext.java`
  - 新增 `homeLightweightMode`

- `DocExpansionStep.java`
  - 首页轻量模式跳过文档扩展

- `RerankStep.java`
  - 首页轻量模式跳过 evidence prefetch

- `KeywordRecallStrategy.java`
  - keyword 文档枚举切到 `kb_doc_search`
  - 支持灰度回退旧 chunk collapse 路径

- `HybridRecallStrategy.java`
  - `/home` hybrid 增加 `kb_doc_search` 文档候选预过滤
  - 支持失败降级

- `KeywordCoarseEvidenceStep.java`
  - 兼容 `kb_doc_search` 顶层字段做 metadata 命中判断

- `SearchAuditLog.java`
  - 新增 doc_search 审计字段

- `application.yml`
  - 新增 `search.doc-search.*` 配置

- `V7__search_windows_and_observability.sql`
  - 新增审计表字段

- `search_audit_log.sql`
  - 同步审计表字段

### 文档

- `docs/production_100m_chunk_search_optimization_plan.md`
- `docs/phase3_search_gray_observability_runbook.md`

## 9. 验收标准

### 9.1 构建验收

必须通过：

```bash
mvn -q -DskipTests compile
python -m py_compile ai_service/core/indexing/es_setup.py \
  ai_service/core/indexing/doc_indexer.py \
  ai_service/core/rag_pipeline.py \
  ai_service/scripts/backfill_kb_doc_search.py \
  ai_service/scripts/search_latency_probe.py
```

当前状态：已通过。

### 9.2 索引验收

必须验证：

- `kb_doc_search_v1` 存在。
- `kb_doc_search` 读别名存在。
- `kb_doc_search_write` 写别名存在且 `is_write_index=true`。
- mapping 中 `title.ngram`、`source.ngram`、`document_number.ngram` 可用。
- `acl_tokens` 字段可 terms 过滤。

### 9.3 数据验收

必须验证：

- 新入库文档能写入 `kb_doc_search_write`。
- 历史文档能通过 backfill 写入。
- 同一文档新版本写入后旧版本 `is_latest=false`。
- `chunk_count`、`keywords`、`section_titles`、`summary` 不为空或符合预期。

### 9.4 检索正确性验收

必须覆盖以下 case：

- 精确文号查询。
- 文件名查询。
- 标题查询。
- 单关键词查询。
- 多关键词 AND 查询。
- 关键词分散在同一文档不同 chunk 的查询。
- 权限可见文档查询。
- 权限不可见文档查询。
- `kb_doc_search` 关闭后的回退查询。
- `/home` prefilter 关闭后的回退查询。

### 9.5 性能验收

必须进行 A/B 压测：

- A 组：关闭 `SEARCH_DOC_SEARCH_ENABLED`
- B 组：开启 `SEARCH_DOC_SEARCH_ENABLED`

对比：

- P50
- P90
- P95
- P99
- QPS
- 错误率
- `doc_search_candidates`
- `post_filter_denied_count`

## 10. 上线步骤

1. 部署包含 Phase 1-3 的代码。
2. 执行 ES 初始化：

```bash
ES_SETUP_MODE=init python scripts/es_init.py
```

3. 验证 `kb_doc_search` 索引和别名。
4. 对历史数据执行 dry run：

```bash
DRY_RUN=true python ai_service/scripts/backfill_kb_doc_search.py
```

5. 正式回填：

```bash
python ai_service/scripts/backfill_kb_doc_search.py
```

6. 开启 keyword 灰度：

```bash
SEARCH_DOC_SEARCH_ENABLED=true
SEARCH_DOC_SEARCH_KEYWORD_ENABLED=true
SEARCH_DOC_SEARCH_HOME_PREFILTER_ENABLED=false
```

7. 压测 keyword。
8. 开启 `/home` prefilter 灰度：

```bash
SEARCH_DOC_SEARCH_HOME_PREFILTER_ENABLED=true
```

9. 压测 `/home` hybrid。
10. 观察审计表和日志指标。

## 11. 回滚方案

按影响范围从小到大回滚：

1. 关闭 `/home` hybrid 预过滤：

```bash
SEARCH_DOC_SEARCH_HOME_PREFILTER_ENABLED=false
```

2. 关闭 keyword 文档级索引：

```bash
SEARCH_DOC_SEARCH_KEYWORD_ENABLED=false
```

3. 关闭全部 `kb_doc_search`：

```bash
SEARCH_DOC_SEARCH_ENABLED=false
```

4. 如索引数据异常，保留索引但停止写入，后续重建 `kb_doc_search_v2` 并切别名。

## 12. 未完成任务

### P0

- 在真实 ES + Java 环境完成接口联调。
- 跑 `ES_SETUP_MODE=init` 创建索引。
- 跑历史数据 backfill。
- 验证 keyword 查询正确性。
- 验证 `/home` hybrid 查询正确性。
- 输出 A/B 压测报告。

### P1

- 为 `backfill_kb_doc_search.py` 增加 checkpoint。
- 为 backfill 增加速率限制、失败重试、批次统计。
- 实现完整文档级 hybrid 召回。
- 将文档相似度检索完全迁移到 `kb_doc_meta` 文档向量。
- 建立监控看板。

### P2

- ES 多节点扩容和 shard 规划。
- 冷热分层和 ILM。
- `kb_doc_search_v2` 零停机重建 SOP。
- 自动化回归测试集。
- 生产容量模型和成本评估。

## 13. 风险与应对

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| `kb_doc_search` 字段聚合不足 | keyword 漏召回 | backfill 后抽样验证，补充字段聚合策略 |
| 文档级预过滤候选过少 | hybrid 漏召回 | 默认 fail-open；候选数配置上限可调 |
| ACL 投影不一致 | 权限风险 | ES ACL 过滤 + PermissionGuard 后置兜底 |
| backfill 时间过长 | 上线延期 | 分批执行，按索引/时间窗口拆分 |
| 单机 ES 资源不足 | P99 不稳定 | 增加节点、拆分索引、冷热分层 |
| 向量索引内存过高 | ES heap/native memory 压力 | 文档级向量走 `kb_doc_meta`，chunk KNN 降低在线使用比例 |

## 14. 附录：关键命令

编译：

```bash
cd java_service
mvn -q -DskipTests compile
```

Python 语法检查：

```bash
python -m py_compile ai_service/core/indexing/es_setup.py \
  ai_service/core/indexing/doc_indexer.py \
  ai_service/core/rag_pipeline.py \
  ai_service/scripts/backfill_kb_doc_search.py \
  ai_service/scripts/search_latency_probe.py
```

keyword 压测：

```bash
python ai_service/scripts/search_latency_probe.py \
  --url http://localhost:8080 \
  --endpoint /api/v1/search \
  --mode keyword \
  --query "任职 公示" \
  -n 100 -c 10
```

`/home` 压测：

```bash
python ai_service/scripts/search_latency_probe.py \
  --url http://localhost:8080 \
  --endpoint /api/v1/search/home \
  --mode hybrid \
  --queries-file queries.txt \
  -n 50 -c 5
```
