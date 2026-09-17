# 知识库升级 v2.0 过渡版 - Hybrid 主流程空候选跳过测试报告

## 任务目标

上一轮已验证 `shouldRunChunkTextRecall(...)` 和 `shouldRunChunkKnn(...)` 的决策边界。本轮进一步验证 `HybridRecallStrategy.recall(...)` 主流程在 `doc_search` 预筛候选为空时，是否真的不会触发 BM25、KNN、Sparse 和 SubQuery KNN 的全局 chunk 召回。

## 本轮改动

1. `HybridRecallStrategyPrefilterConditionTest`
   - 新增 `recallSkipsAllChunkRecallWhenDocSearchPrefilterReturnsEmptyCandidates`。
   - 使用 mock 注入 `ElasticsearchClient`、`AiEngineGateway`、`EsRecallUtils`、`SearchQueryCache`。
   - 构造 `docCandidateSources = emptyList` 的真实 `recall(...)` 场景。
   - 断言：
     - `bm25Response == null`
     - `knnResponse == null`
     - `sparseResponse == null`
     - `bm25Hits/knnHits/sparseHits == 0`
     - `chunk_text_recall_skipped == 1`
     - `knn_skipped == 1`
     - `sub_query_knn_skipped == 1`
     - ES `search(...)` 未被调用
     - AI 稀疏向量 `fetchSparseVector(...)` 未被调用

2. `HybridRecallStrategy`
   - 将主流程 `allOf` 超时从直接读取 `config.getEsQueryTimeout()` 改为 `getEsTimeoutMs(config)`。
   - 将 BM25 请求超时也统一改为 `getEsTimeoutMs(config)`。
   - 目的：配置字段为空时使用默认超时，避免正常跳过路径打印 `allOf 异常: null`。

## 测试命令

```bash
mvn "-Dtest=HybridRecallStrategyPrefilterConditionTest" test
```

## 测试结果

```text
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 验证结论

1. `doc_search` 预筛为空时，Hybrid 主流程不会访问 ES chunk 索引。
2. `doc_search` 预筛为空时，不会调用 AI 稀疏向量接口，避免无结果路径上的额外开销。
3. BM25、KNN、Sparse、SubQuery KNN 均被统一跳过。
4. 配置缺省场景不会再输出 `allOf 异常: null` 误导日志。

## 生产意义

该测试把“决策函数正确”升级为“主流程确实不触发重路径”。这对亿级数据过渡版非常关键，因为真正的性能风险来自主流程是否仍会在空候选时访问全局业务 chunk 索引。

## 剩余风险

1. 本轮仍是单元级验证，未连接真实 ES。
2. 后续需要补充一组真实 ES 回归：
   - `doc_search` 有候选时，BM25/Sparse/KNN 查询必须带 `metadata.source terms`。
   - 候选为空时，接口结果应为空或仅保留 preflight 命中。
   - 候选过宽时，跳过重召回并记录指标。
