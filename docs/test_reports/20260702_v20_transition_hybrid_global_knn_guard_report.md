# 知识库升级 v2.0 过渡版：Hybrid 全局 chunk KNN 收敛测试报告

## 1. 实施背景

v2.0 过渡版暂不拆分 coarse/fine/vector 索引，因此现有业务 chunk 索引仍保存向量字段。若普通 hybrid 在没有文档级候选约束时继续执行 KNN，就会在亿级 chunk 数据下退回全局 HNSW 重路径。

上一轮已让普通 hybrid 默认启用 `kb_doc_search` 预过滤。本轮继续收紧：只有存在小候选集时才允许执行 chunk KNN；无候选或候选过宽时跳过 KNN，避免把查询拖回全局向量召回。

## 2. 本次优化内容

### 2.1 修改文件

- `java_service/src/main/java/com/boyang/search/pipeline/steps/HybridRecallStrategy.java`
- `java_service/src/main/resources/application.yml`
- `java_service/src/test/java/com/boyang/search/pipeline/steps/HybridRecallStrategyPrefilterConditionTest.java`

### 2.2 代码变更

1. 新增配置：

```yaml
search:
  hybrid:
    global-knn:
      enabled: ${SEARCH_HYBRID_GLOBAL_KNN_ENABLED:false}
    candidate-knn-max-sources: ${SEARCH_HYBRID_CANDIDATE_KNN_MAX_SOURCES:200}
```

2. 新增判断方法：

```java
HybridRecallStrategy.shouldRunChunkKnn(context, docCandidateSources)
```

启用条件：

```text
queryVector 不为空
如果启用了 doc_search prefilter：
  docCandidateSources 必须非空
  docCandidateSources.size <= candidate-knn-max-sources
如果没有启用 doc_search prefilter：
  只有 global-knn.enabled=true 才允许执行
```

3. KNN 被跳过时写入 timings：

```text
knn_skipped = 1
knn_skipped_reason = doc_search_candidates_empty_or_too_many / global_knn_disabled
```

## 3. 测试用例

扩展测试类：

```text
HybridRecallStrategyPrefilterConditionTest
```

新增覆盖：

1. `chunkKnnRequiresNonEmptyDocSearchCandidatesWhenPrefilterEnabled`
   - prefilter 启用时，无候选不跑 KNN。
   - 有候选时允许 KNN。

2. `chunkKnnSkipsWhenCandidateSourcesExceedThreshold`
   - 候选数量超过阈值时跳过 KNN。

3. `globalChunkKnnIsDisabledByDefaultWhenPrefilterDisabled`
   - 未启用 prefilter 时，全局 chunk KNN 默认关闭。

4. `globalChunkKnnCanBeExplicitlyEnabledForRollback`
   - 显式开启 `global-knn.enabled=true` 时，可恢复全局 KNN。

同时保留上一轮 prefilter 条件测试：

- 普通 hybrid 默认启用 doc_search prefilter。
- semantic 不启用 hybrid prefilter。
- 首页轻量模式受 home 开关控制。
- doc_search 总开关可关闭所有 prefilter。

## 4. 测试命令与结果

执行目录：

```powershell
E:\project\AI\knowledge-base\java_service
```

执行命令：

```powershell
mvn "-Dtest=HybridRecallStrategyPrefilterConditionTest" test
```

执行结果：

```text
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-07-02T15:23:32+08:00
```

## 5. 生产影响分析

1. 普通 hybrid 不再默认执行全局 chunk KNN。
   - 有 doc_search 小候选集时，KNN 继续参与召回。
   - 无候选或候选过宽时跳过 KNN，保留 BM25/Sparse 召回。

2. 可灰度回滚。
   - 如需临时恢复旧行为，可设置：

```text
SEARCH_HYBRID_GLOBAL_KNN_ENABLED=true
```

3. KNN 执行质量依赖 doc_search 候选质量。
   - 因此生产切换前仍必须完成 `kb_doc_search` 覆盖率和权限字段完整性审计。

4. 该改动只影响 `HybridRecallStrategy` 的 chunk KNN 构造条件。
   - 不改 BM25。
   - 不改 Sparse。
   - 不改 keyword。
   - 不改入库链路。

## 6. 结论

本次 v2.0 过渡版第三项实施完成。

优化后，普通 hybrid 在过渡版中不再默认执行无候选约束的全局 chunk KNN。KNN 仅在 `kb_doc_search` 提供的小候选集内执行，或在显式回滚开关打开时执行。
