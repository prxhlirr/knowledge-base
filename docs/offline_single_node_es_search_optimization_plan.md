# 离线单节点 ES 多索引检索优化实施文档

## 1. 结论先行

当前项目的核心矛盾不是“单分片 500w 文档一定需要更多分片”，而是：单节点 ES、多角色多物理索引、候选窗口截断、精排窗口偏小、精确字段召回不足、以及小显存大模型并发风险叠加在一起，导致用户感觉“搜得慢、搜不准、50 条也找不到想要的文档”。

本轮实施遵循三个底线：

1. 保留“一角色/一知识库一物理索引”的物理隔离架构。
2. 当前单 ES 节点继续使用 `number_of_replicas=0`，不引入无效副本。
3. 优先修复项目中已验证的确定性缺陷，再做可观测、可回滚的参数放大。

## 2. 已验证的项目事实

代码与配置检查结果：

- `ai_service/core/indexing/es_setup.py` 当前索引模板为 `number_of_shards=1`、`number_of_replicas=0`，符合单节点 ES。
- `docs/es-mapping.json` 显示主向量字段为 `vector`，不是 `dense_vector`。
- `metadata.document_number` 为 `keyword`，`metadata.title` 为 `text + keyword`，可支持文号/标题精确召回。
- Java 检索链路为 `SearchServiceV2` 管道，关键节点包括 `VectorFetchStep`、`EsRecallStep`、`RrfFusionStep`、`DocExpansionStep`、`RerankStep`。
- `HybridRecallStrategy` 子句 KNN 扩展中存在 `field("dense_vector")`，会导致该扩展通道查错字段。
- `RrfFusionStep` 中 RRF 平滑因子写死为 `60`，没有使用 `SysAiTuningConfig#getRrfK()`。
- `SearchController` 将返回 `pageSize` 限制为 50，且 `dynamicTopK = min(pageNum * pageSize, 200)`，返回条数与召回/精排窗口耦合明显。
- `RerankStep` 已有 ColBERT semaphore，但 Java 网关层原先没有统一保护 LLM/Ollama 入口。

## 3. 对原方案的修正

不建议在当前单节点环境直接采用：

- `number_of_replicas=1`：单节点没有可分配副本，会造成 yellow，不能提升可用性，也不能读写分离。
- 微型 1、中型 3、大型 5 的默认动态分片：多角色多索引场景会扩大 shard 数，增加 JVM 堆、文件句柄和段管理成本；单节点下并行收益不稳定。
- 自动 close/open 角色索引：会引入首次查询抖动、权限路由复杂度和运维不可预期性，不适合作为第一阶段优化。
- 对所有 AI 调用共用一个 semaphore：会误伤 embedding/BGE reranker 等 CPU/ONNX 或独立服务能力。

建议的单节点分片原则：

- 默认：`1 primary / 0 replica`。
- 单个物理索引达到 `30GB~80GB` 且查询热点高：评估新建索引使用 `2 primary`。
- 单个物理索引达到 `80GB~150GB`：评估 `3 primary` 或按知识库归档拆分。
- 当前总量 100GB 但单分片不大时，优先治理候选窗口、精确召回和并发，而不是扩 shard。

## 4. 实施阶段

### P0 已开始实施

目标：修复确定性问题，不改变安全边界。

- 修复 `HybridRecallStrategy` 子句 KNN 字段：`dense_vector` -> `vector`。
- 修复 `RrfFusionStep`：RRF k 使用 DB 配置 `config.getRrfK()`。
- 增加 Java 网关 LLM semaphore：
  - 默认 `AI_LLM_MAX_CONCURRENCY=2`。
  - 默认 `AI_LLM_ACQUIRE_TIMEOUT_MS=500`。
  - 覆盖 `fetchChatCompletion`、`openChatStream`、`fetchLlmRerankScores`。
  - 获取许可失败时降级，不继续压 Ollama/GPU。

### P1 下一步建议

目标：解决“50 条里找不到目标文档”的召回和排序问题。

- 解耦参数：`returnTopK`、`recallTopK`、`fusionTopK`、`rerankTopK` 不再混用一个 `topK`。
- 将混合检索候选池默认提高到 `200~400`，返回仍可保持前端 50 条。
- 将 GPU 模式 `rerankLimit` 从 15 提升到 50，压测后再评估 80。
- 将 `MAX_GLOBAL_CHARS=3500` 改成可配置项，建议初始 12000。
- 增强 pre-flight：
  - `metadata.document_number` 精确匹配。
  - `metadata.title.keyword` 精确匹配。
  - `metadata.source` / 文件名精确匹配。
  - 命中后进入 FastTrack 或在 RRF/LTR 中做高置信补分。

### P2 可观测与运维

目标：能知道慢在哪里、噪声从哪里来。

- 检索日志增加：
  - `resolved_index`
  - `bm25_hits`
  - `knn_hits`
  - `sparse_hits`
  - `rrf_candidates`
  - `rerank_input_count`
  - `rerank_degraded`
  - `llm_semaphore_rejected`
- 单节点 ES 增加本地 snapshot repository，低峰定时增量快照。
- 增加 `_cat/segments`、慢查询日志、JVM heap、query latency 的离线巡检脚本。

### P1/P2 implement notes

- Added `returnTopK`, `recallTopK`, `fusionTopK`, `rerankTopK`, and `rerankGlobalMaxChars`.
- API `pageSize` still caps at 50; internal recall/fusion/rerank windows are now DB-configurable.
- Added shared `LiteralRecallStep` before mode-specific recall for exact `document_number`, `title.keyword`, and `source` hits.
- Added search audit dimensions for resolved index, mode, channel hits, RRF candidate count, rerank input count, and rerank degradation.
- Added manual single-node ES ops helper: `scripts/es_single_node_ops.ps1`.
- Single-node ES remains `0` replicas; no automatic index close/open is introduced.

## 5. 验证方案

最低验证：

1. 编译 Java 服务，确认字段修复与 semaphore 改造不破坏构建。
2. 用文号样例，例如 `[2026] 001号`，验证 `metadata.document_number` 精确命中优先级。
3. 用并发 5 的问答/流式问答请求压测，确认最多 2 个进入 LLM，其余请求快速降级。
4. 对同一查询记录修改前后：
   - ES 三路召回数
   - RRF 候选数
   - 精排输入数
   - 首包延迟和总耗时

## 6. 回滚方式

- `HybridRecallStrategy` 字段修复无需回滚，mapping 已验证主字段为 `vector`。
- `RrfFusionStep` 如需回滚，可将 `config.getRrfK()` 恢复为 `60`。
- LLM semaphore 可通过环境变量放宽：`AI_LLM_MAX_CONCURRENCY=8`，或将获取超时调大。
