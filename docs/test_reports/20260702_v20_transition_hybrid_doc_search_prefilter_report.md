# 知识库升级 v2.0 过渡版：Hybrid 文档级预过滤测试报告

## 1. 实施背景

v2.0 过渡版暂不拆分 coarse/fine/vector 索引，因此普通 `/search` hybrid 不能继续默认直接在现有业务 chunk 索引上做 BM25/KNN/Sparse 三路全局召回。

本轮排查确认：`HybridRecallStrategy` 的 `kb_doc_search` 预过滤仅在 `homeLightweightMode=true` 时启用，普通 `/search` hybrid 不启用。`SearchServiceV2` 的并行预取条件同样只判断首页轻量模式。

这会导致普通 hybrid 在亿级业务索引下仍保留重路径。

## 2. 本次优化内容

### 2.1 修改文件

- `java_service/src/main/java/com/boyang/search/pipeline/steps/HybridRecallStrategy.java`
- `java_service/src/main/java/com/boyang/search/service/SearchServiceV2.java`
- `java_service/src/main/resources/application.yml`
- `java_service/src/test/java/com/boyang/search/pipeline/steps/HybridRecallStrategyPrefilterConditionTest.java`

### 2.2 代码变更

1. 新增普通 hybrid 文档级预过滤开关：

```yaml
search:
  doc-search:
    hybrid-prefilter-enabled: ${SEARCH_DOC_SEARCH_HYBRID_PREFILTER_ENABLED:true}
```

2. 保留首页独立开关：

```yaml
search:
  doc-search:
    home-prefilter-enabled: ${SEARCH_DOC_SEARCH_HOME_PREFILTER_ENABLED:true}
```

3. 新增统一判断方法：

```java
HybridRecallStrategy.shouldUseDocSearchPrefilter(context)
```

启用语义：

```text
doc_search 总开关关闭 -> 不启用
homeLightweightMode=true -> 使用 home-prefilter-enabled
普通 searchMode=hybrid -> 使用 hybrid-prefilter-enabled
semantic -> 暂不启用
```

4. `SearchServiceV2` 并行预取改用统一判断。

目的：

```text
普通 hybrid 也能在 VectorFetch 并行阶段提前执行 DocSearchPrefilter
避免进入 HybridRecallStrategy 后才同步查询 kb_doc_search
```

## 3. 测试用例

新增测试类：

```text
HybridRecallStrategyPrefilterConditionTest
```

覆盖用例：

1. `normalHybridSearchUsesDocSearchPrefilterByDefault`
   - 普通 `/search` hybrid 默认启用文档级预过滤。

2. `semanticSearchDoesNotUseHybridPrefilter`
   - semantic 模式暂不启用普通 hybrid prefilter。

3. `homeLightweightModeUsesHomePrefilterSwitch`
   - 首页轻量模式受 `home-prefilter-enabled` 独立控制。

4. `docSearchMasterSwitchDisablesAllPrefilter`
   - doc_search 总开关关闭时，所有 prefilter 都关闭。

同时回归：

```text
KeywordRecallStrategyLegacyFallbackTest
```

确保 keyword legacy fallback 收敛逻辑不受影响。

## 4. 测试命令与结果

执行目录：

```powershell
E:\project\AI\knowledge-base\java_service
```

执行命令：

```powershell
mvn "-Dtest=HybridRecallStrategyPrefilterConditionTest,KeywordRecallStrategyLegacyFallbackTest" test
```

执行结果：

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-07-02T15:20:41+08:00
```

## 5. 生产影响分析

1. 普通 hybrid 默认开始使用 `kb_doc_search` 候选预过滤。
   - 后续 BM25/KNN/Sparse 会带 `metadata.source terms` 候选过滤。
   - 可显著降低现有业务 chunk 索引的全局召回压力。

2. 首页和普通 hybrid 可独立回滚。
   - 首页问题：调整 `SEARCH_DOC_SEARCH_HOME_PREFILTER_ENABLED=false`
   - 普通 hybrid 问题：调整 `SEARCH_DOC_SEARCH_HYBRID_PREFILTER_ENABLED=false`

3. semantic 暂不启用该 prefilter。
   - 本轮只处理普通 hybrid，避免扩大行为变化。

4. 对 `kb_doc_search` 覆盖率提出更高要求。
   - 若 `kb_doc_search` 数据缺失，普通 hybrid 候选裁剪可能偏窄。
   - 切生产前必须执行 doc_search 覆盖率和权限字段完整性审计。

## 6. 结论

本次 v2.0 过渡版第二项实施完成。

优化后，普通 `/search` hybrid 不再只在首页轻量模式下才享受文档级预过滤。默认情况下，普通 hybrid 也会先通过 `kb_doc_search` 缩小候选文档，再对现有业务 chunk 索引执行受限召回。
