# 知识库升级 v2.0 过渡版 - Hybrid chunk 文本召回收敛测试报告

## 任务目标

在暂不拆分 coarse/fine/vector 独立索引的过渡阶段，继续收敛 Hybrid 检索链路，避免 `doc_search` 预筛为空、失败或候选过宽时，BM25、Sparse、SubQuery KNN 自动回退到全局业务 chunk 索引。

## 本轮改动

1. `HybridRecallStrategy`
   - 新增 `shouldRunChunkTextRecall(...)`，统一判断 BM25/Sparse 是否允许执行。
   - `doc_search` 预筛启用时，BM25/Sparse 仅在候选 source 非空且数量不超过阈值时执行。
   - `doc_search` 预筛未启用时，BM25/Sparse 默认不执行全局 chunk 召回，必须显式开启回滚开关。
   - Sparse 在跳过时不会再请求稀疏向量，避免无意义 AI 调用。
   - SubQuery KNN 复用上一轮 KNN 开关，且补齐权限过滤、最新版本过滤、粗粒度过滤、data_source 过滤和候选 source 过滤。
   - `doc_search` 预筛异常日志从“fallback to full chunk recall”修正为“return empty candidates”，避免误导生产排障。

2. `application.yml`
   - 新增 `SEARCH_HYBRID_GLOBAL_CHUNK_RECALL_ENABLED`，默认 `false`。
   - 新增 `SEARCH_HYBRID_CANDIDATE_CHUNK_MAX_SOURCES`，默认 `200`。

3. `HybridRecallStrategyPrefilterConditionTest`
   - 新增 4 个文本召回边界测试。
   - 覆盖空候选、候选过宽、默认禁止全局 chunk 召回、显式回滚开启全局 chunk 召回。

## 配置说明

| 配置项 | 默认值 | 作用 |
| --- | --- | --- |
| `SEARCH_HYBRID_GLOBAL_CHUNK_RECALL_ENABLED` | `false` | 是否允许 Hybrid 在无 `doc_search` 候选约束时执行全局 BM25/Sparse chunk 召回 |
| `SEARCH_HYBRID_CANDIDATE_CHUNK_MAX_SOURCES` | `200` | `doc_search` 候选 source 数量超过该值时跳过 BM25/Sparse |
| `SEARCH_HYBRID_GLOBAL_KNN_ENABLED` | `false` | 是否允许无候选约束的全局 chunk KNN |
| `SEARCH_HYBRID_CANDIDATE_KNN_MAX_SOURCES` | `200` | `doc_search` 候选 source 数量超过该值时跳过 chunk KNN |

## 测试命令

```bash
mvn "-Dtest=HybridRecallStrategyPrefilterConditionTest" test
```

## 测试结果

```text
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 已验证场景

1. 普通 `/search` hybrid 默认启用 `doc_search` 预筛。
2. semantic 模式不复用 hybrid 预筛开关。
3. 首页轻量模式仍使用首页独立预筛开关。
4. `doc_search` 总开关关闭后，所有预筛关闭。
5. KNN 在预筛启用时要求候选非空。
6. KNN 在候选数量超过阈值时跳过。
7. 全局 KNN 默认关闭。
8. 全局 KNN 可通过回滚开关显式开启。
9. BM25/Sparse 在预筛启用时要求候选非空。
10. BM25/Sparse 在候选数量超过阈值时跳过。
11. 全局 BM25/Sparse 默认关闭。
12. 全局 BM25/Sparse 可通过回滚开关显式开启。

## 生产影响

默认配置下，Hybrid 召回的重路径已从“全局 chunk 索引三路 fan-out”收敛为“先查文档级 `kb_doc_search`，再在小候选 source 内查 chunk”。这符合 v2.0 过渡版目标：保留现有业务索引结构，同时避免亿级数据下无边界 fan-out。

## 回滚方式

如生产验证发现 `kb_doc_search` 覆盖不完整，临时回滚可以打开：

```bash
SEARCH_HYBRID_GLOBAL_CHUNK_RECALL_ENABLED=true
SEARCH_HYBRID_GLOBAL_KNN_ENABLED=true
```

该回滚会恢复全局 chunk 召回能力，但亿级数据下不建议长期使用。

## 剩余风险

1. 当前单测验证的是决策边界，没有启动真实 ES 验证 DSL 结果。
2. 生产上线前仍需用真实 `kb_doc_search` 和业务 chunk 索引跑一轮 `/search` 回归，重点观察：
   - `doc_search_prefilter_candidates`
   - `chunk_text_recall_skipped`
   - `knn_skipped`
   - `sub_query_knn_skipped`
   - 搜索结果数量和 topN 相关性
3. 如果历史数据没有完整补齐 `kb_doc_search.source/source_index/acl_tokens`，默认配置会导致相关文档不可召回，需要先完成历史数据补齐或临时开启回滚开关。
