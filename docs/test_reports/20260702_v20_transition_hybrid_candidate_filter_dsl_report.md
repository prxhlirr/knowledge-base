# 知识库升级 v2.0 过渡版 - Hybrid 候选 source 过滤 DSL 测试报告

## 任务目标

验证 `doc_search` 预筛存在候选文档时，Hybrid 主流程发往业务 chunk 索引的 BM25、KNN、Sparse 请求是否都带上 `metadata.source terms` 过滤，避免“有候选但仍全局扫描”的回归。

## 本轮改动

1. `HybridRecallStrategyPrefilterConditionTest`
   - 新增 `recallAddsCandidateSourceFilterToAllChunkRequestsWhenDocSearchPrefilterHasCandidates`。
   - 构造 `docCandidateSources = [doc-a]`。
   - 构造非空 `queryVector` 和 `querySparseVector`，确保 BM25、KNN、Sparse 三路均进入请求构建。
   - mock `ElasticsearchClient.search(...)` 返回空响应，避免依赖真实 ES。
   - 捕获 3 个 `SearchRequest`。
   - 使用 `JacksonJsonpMapper` 将请求序列化为 JSON。
   - 逐个断言请求 JSON 包含：
     - `"metadata.source"`
     - `"doc-a"`

2. 测试辅助方法
   - 新增 `emptySearchResponse()`，用于稳定返回空 ES 响应。
   - 新增 `toJson(SearchRequest request)`，用 ES Java Client 官方序列化路径验证最终 DSL。

## 测试命令

```bash
mvn "-Dtest=HybridRecallStrategyPrefilterConditionTest" test
```

## 测试结果

```text
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 已验证场景

1. `doc_search` 候选为空时，主流程不访问 chunk 索引。
2. `doc_search` 候选非空时，BM25 请求带 `metadata.source terms`。
3. `doc_search` 候选非空时，KNN 请求带 `metadata.source terms`。
4. `doc_search` 候选非空时，Sparse 请求带 `metadata.source terms`。

## 生产意义

这一步把 v2.0 过渡版的核心性能约束从“是否执行”推进到“执行时是否带边界”。在现有业务索引暂不拆 coarse/fine/vector 的阶段，只有确保所有 chunk 请求都被 `doc_search` 文档候选收窄，才能避免亿级数据下的全局 fan-out。

## 剩余风险

1. 当前测试验证 BM25、KNN、Sparse 三路；SubQuery KNN 的 DSL 过滤已在代码中补齐，但尚未单独捕获序列化验证。
2. 后续应增加 SubQuery KNN 的专项测试，构造多子句弱 BM25 场景，验证其请求也包含权限、最新版本、粗粒度、`data_source` 和候选 source 过滤。
