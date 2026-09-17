# 知识库升级 v2.0 过渡版：Keyword Legacy Fallback 收敛测试报告

## 1. 实施背景

v2.0 过渡版暂不拆分 coarse/fine/vector 索引，但必须先改变默认检索路径：

```text
先查 kb_doc_search 文档级候选
再受限回表现有业务 chunk 索引
```

本轮排查确认：`KeywordRecallStrategy` 在 `kb_doc_search` 开启时，仍会并行执行旧 chunk 索引召回。也就是说，即使文档级索引已经启用，亿级场景下旧的 `kb_document_* + collapse(metadata.source)` 重路径仍会被默认打出去。

这与 v2.0 过渡版目标冲突。

## 2. 本次优化内容

### 2.1 修改文件

- `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordRecallStrategy.java`
- `java_service/src/main/resources/application.yml`
- `java_service/src/test/java/com/boyang/search/pipeline/steps/KeywordRecallStrategyLegacyFallbackTest.java`

### 2.2 代码变更

1. 新增配置：

```yaml
search:
  keyword:
    legacy-fallback:
      enabled: ${SEARCH_KEYWORD_LEGACY_FALLBACK_ENABLED:false}
```

2. keyword 默认行为调整为：

```text
doc_search enabled -> 只查询 kb_doc_search
doc_search 无交集 -> 返回空候选
doc_search 异常 -> 返回空候选
```

3. 只有显式开启以下配置时，才允许回退旧 chunk 召回：

```text
SEARCH_KEYWORD_LEGACY_FALLBACK_ENABLED=true
```

4. 增加 timing 标记：

```text
keyword_legacy_fallback = 0 / 1
```

用于灰度观测和生产审计。

5. 将 `searchCandidateDocsByTerm` 与 `searchLegacyCandidateDocsByTerm` 放宽为包内可见。

目的：允许同包单元测试覆写查询方法并计数，验证是否调用 legacy 路径。该调整不改变外部 API。

## 3. 测试用例

新增测试类：

```text
KeywordRecallStrategyLegacyFallbackTest
```

覆盖用例：

1. `docSearchEmptyDoesNotCallLegacyFallbackByDefault`
   - 条件：`kb_doc_search` 返回空
   - 配置：`legacyFallbackEnabled=false`
   - 期望：
     - doc_search 查询调用 1 次
     - legacy 查询调用 0 次
     - `keyword_legacy_fallback=0`
     - `doc_search_enabled=1`

2. `docSearchEmptyCallsLegacyOnlyWhenFallbackIsEnabled`
   - 条件：`kb_doc_search` 返回空
   - 配置：`legacyFallbackEnabled=true`
   - 期望：
     - doc_search 查询调用 1 次
     - legacy 查询调用 1 次
     - `keyword_legacy_fallback=1`
     - `doc_search_enabled=0`

同时回归：

- `KeywordRecallStrategySourceFilterTest`
- `KeywordRecallStrategyLiteralQueryTest`

确保权限投影字段 `_source.includes` 和 keyword literal 查询逻辑不受影响。

## 4. 测试命令与结果

执行目录：

```powershell
E:\project\AI\knowledge-base\java_service
```

执行命令：

```powershell
mvn "-Dtest=KeywordRecallStrategyLegacyFallbackTest,KeywordRecallStrategySourceFilterTest,KeywordRecallStrategyLiteralQueryTest" test
```

执行结果：

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-07-02T15:16:45+08:00
```

## 5. 生产影响分析

1. keyword 默认不再把旧 chunk 重路径常态化。
   - 亿级场景下可避免每次 keyword 都额外触发 `kb_document_*` collapse 查询。

2. 召回完整性依赖 `kb_doc_search` 覆盖率。
   - 因此切换前必须确保 `kb_doc_search` 历史回填和权限字段校验通过。

3. 保留应急回滚能力。
   - 如发现 `kb_doc_search` 覆盖不足，可临时设置：

```text
SEARCH_KEYWORD_LEGACY_FALLBACK_ENABLED=true
```

4. fallback 可观测。
   - 通过 `keyword_legacy_fallback` 可统计旧路径使用率。

## 6. 结论

本次 v2.0 过渡版第一项实施完成。

优化后，keyword 检索默认只依赖文档级 `kb_doc_search` 做文档发现，不再并行触发旧 chunk collapse 重路径。旧路径保留为显式配置开关，便于灰度和应急回滚。
