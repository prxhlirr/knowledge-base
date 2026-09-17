# 1 亿级 Chunk 生产检索优化实施规划

## 背景与目标

当前 `/home` 会在一次用户请求中同时执行 keyword、hybrid、QA evidence 和 LLM 回答链路。若单索引达到 1 亿+ chunk，在线请求不能再把 `kb_document_v1` 当作主召回入口，否则 keyword 的全库 BM25/collapse、hybrid 的全库 KNN/sparse、DocExpansion、evidence prefetch 和 rerank 会叠加成不可控尾延迟。

目标：

- keyword P95 <= 1.5s。
- hybrid 检索 P95 <= 3s。
- QA evidence ready P95 <= 3s，LLM 首 token 单独统计。
- 文档相似度 P95 <= 2s。
- 权限误放行 = 0。
- ES timeout = 0。

核心原则：

```text
文档级召回 -> 粗粒度召回 -> 细粒度 chunk 验证/取证 -> 小窗口精排 -> QA/相似度后处理
```

## 目标索引体系

### kb_doc_search_v1

文档级 keyword 候选召回索引，每篇文档一条。

字段：

- `doc_id`
- `content_hash`
- `source`
- `title`
- `document_number`
- `keywords`
- `tags`
- `entities`
- `section_titles`
- `doc_terms`
- `acl_tokens`
- `is_latest`
- `data_source`
- `doc_type`
- `publish_time`
- `chunk_count`
- `representative_chunk_ids`

### kb_doc_vector_v1

文档级语义召回和文档相似度索引，每篇文档一条。

字段：

- `doc_id`
- `source`
- `title`
- `doc_vector`
- `doc_terms`
- `acl_tokens`
- `is_latest`
- `data_source`
- `doc_type`

### kb_chunk_coarse_v1

粗粒度 chunk 索引，用于 hybrid 主召回补充。

### kb_document_v1

现有 1 亿+ fine chunk 索引，角色调整为全文验证、证据片段、详情页 chunk 检索，不再作为 `/home` 默认主召回入口。

## 阶段规划

### Phase 0：观测与首页热链路止血

任务：

- `/home` hybrid 增加轻量模式。
- 首页 hybrid 跳过 `DocExpansionStep`。
- 首页 hybrid 跳过 `RerankStep` 的 evidence prefetch。
- 保留普通 `/search` 和 QA 链路现有行为。
- 增加 timing 标记：`doc_expansion_skipped_home`、`evidence_prefetch_skipped_home`。

验收：

- `/home` hybrid 不再同步执行 sibling/parent chunk 扩展。
- `/home` hybrid 不再同步补邻近 evidence chunks。
- 普通搜索结果行为不变。

### Phase 1：kb_doc_search 索引与历史迁移

任务：

- 新增 `kb_doc_search_v1` mapping 和 alias。
- 在入库链路中写入文档级记录。
- 编写 `scripts/backfill_kb_doc_search.py`：
  - composite agg 枚举历史 doc key。
  - 分批拉取 chunks。
  - 聚合标题、文号、关键词、实体、章节标题、ACL、版本信息。
  - bulk upsert 到 `kb_doc_search_v1`。
  - 支持 checkpoint 和失败队列。
- ACL 投影同步更新 `kb_doc_search_v1`。

验收：

- `kb_doc_search_v1` 文档数 >= 最新文档数的 99.5%。
- ACL 缺失率 = 0，或全部进入失败队列。
- 随机样本字段准确率 >= 99%。

### Phase 2：keyword 新链路

任务：

- 新增 `KeywordDocRecallStep`。
- 新增 `KeywordChunkVerifyStep`。
- 新增 `KeywordEvidenceAssembleStep`。
- keyword pipeline 改为：

```text
QueryNormalizeStep
LiteralRecallStep
KeywordDocRecallStep
KeywordChunkVerifyStep
KeywordEvidenceAssembleStep
KeywordRankStep
KeywordResultAssembleStep
```

- 保留旧 `KeywordRecallStrategy` 链路作为 fallback。

验收：

- keyword 不再默认在 1 亿 chunk 上做全局 collapse。
- 文档级候选必须经过 chunk 全文覆盖验证后才能返回。
- 黄金集 top5 命中率不下降。

### Phase 3：hybrid 分层召回

任务：

- 新增 `DocVectorRecallStrategy`。
- 新增 `CoarseChunkRecallStrategy`。
- hybrid 改为：

```text
kb_doc_search BM25 topN
kb_doc_vector KNN topN
kb_chunk_coarse BM25/KNN topN
RRF by doc_id
kb_document_v1 restricted evidence fetch
small-window rerank
```

验收：

- hybrid 不在 fine chunk 1 亿空间上默认做 KNN。
- 首页 `rerankTopK` 控制在 12~24。
- hybrid P95 <= 3s。

### Phase 4：文档相似度迁移

任务：

- 文档相似度统一走 `kb_doc_vector_v1`。
- topN 文档级候选后，只对 top20 做 chunk-level 复核。

验收：

- 文档相似度不再对 1 亿 chunk vector 做全量检索。
- P95 <= 2s。

### Phase 5：集群与资源扩展

任务：

- ES 独立集群：3 master-only、3~6 hot data、2 coordinating。
- hot data 使用 NVMe、128GB~256GB RAM、31GB~32GB heap。
- AI 按能力池拆分：
  - embedding/ColBERT
  - rerank
  - LLM
  - OCR/worker
- 在线 AI 节点必须 `AI_START_WORKER=false`。

验收：

- 查询不再打全库 alias。
- 在线检索不被 OCR/入库/LLM 挤占 GPU。

## 第一批代码落地范围

本轮先落 Phase 0：

- `SearchContext` 增加 `homeLightweightMode`。
- `SearchServiceV2` 增加首页专用 context 入口。
- `/search/home` 调用首页专用入口。
- `DocExpansionStep` 在首页轻量模式下跳过。
- `RerankStep` 在首页轻量模式下跳过 evidence prefetch。

这组改动不改变普通搜索行为，是后续索引级改造前的低风险降延迟步骤。
