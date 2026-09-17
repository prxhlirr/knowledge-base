# 知识库升级 v2.0 过渡版 - KeywordRecallStrategy 候选窗口配置化测试报告

## 一、任务目标

从第一性原理看，keyword 模式的核心成本不在向量召回，而在“每个关键词枚举多少文档候选”。原逻辑固定为 `min(500, max(topK * 12, 120))`，在不同索引规模、关键词稀疏度、业务 topK 下不可调。亿级数据下，候选窗口过大会放大 ES 压力，过小会导致多词交集召回不足。

本轮目标：

- 将 keyword 文档候选最大窗口配置化。
- 将 keyword 文档候选最小窗口配置化。
- 将 keyword 文档候选 topK 放大倍数配置化。
- 保持历史默认行为不变。
- 新旧 `kb_doc_search` 与 legacy fallback 两条候选枚举路径共用同一窗口计算。

## 二、实施内容

### 1. 代码变更

文件：

- `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordRecallStrategy.java`

新增配置项：

| 配置项 | 环境变量 | 默认值 | 作用 |
| --- | --- | ---: | --- |
| `search.keyword.doc-candidate-max` | `SEARCH_KEYWORD_DOC_CANDIDATE_MAX` | `500` | keyword 每词文档候选最大窗口 |
| `search.keyword.doc-candidate-min` | `SEARCH_KEYWORD_DOC_CANDIDATE_MIN` | `120` | keyword 每词文档候选最小窗口 |
| `search.keyword.doc-candidate-multiplier` | `SEARCH_KEYWORD_DOC_CANDIDATE_MULTIPLIER` | `12` | keyword 每词文档候选相对 topK 的放大倍数 |

关键规则：

- 默认值保持历史 `min(500, max(topK * 12, 120))` 行为。
- 配置为空、非数字、`<=0` 时自动回落默认值。
- `topK <= 0` 时按 `1` 处理，避免异常入参导致窗口为 0 或负数。
- `searchCandidateDocsByTerm`、`searchLegacyCandidateDocsByTerm`、`searchCandidateDocs` 三处均改为调用 `resolveKeywordCandidateSize(topK)`。

### 2. 测试变更

文件：

- `java_service/src/test/java/com/boyang/search/pipeline/steps/KeywordRecallStrategyCandidateSizeConfigTest.java`

覆盖测试：

- `keywordCandidateSizeKeepsHistoricalDefaultWindow`
- `keywordCandidateSizeUsesConfiguredWindow`
- `keywordCandidateSizeFallbackWhenConfiguredValuesAreInvalid`
- `keywordCandidateSizeTreatsNonPositiveTopKAsOne`

## 三、执行验证

### 1. 单元测试

执行命令：

```bash
mvn -q -Dtest=KeywordRecallStrategyCandidateSizeConfigTest -DforkCount=0 test
```

执行结果：

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

结论：通过。

### 2. 静态核对

执行命令：

```bash
rg -n 'MAX_DOC_CANDIDATES|topK \* 12|Math\.max\(topK \* 12, 120\)|SEARCH_KEYWORD_DOC_CANDIDATE|keywordDocCandidate|resolveKeywordCandidateSize|resolvePositiveInt' java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordRecallStrategy.java java_service/src/test/java/com/boyang/search/pipeline/steps/KeywordRecallStrategyCandidateSizeConfigTest.java
```

核对结论：

- 旧 `MAX_DOC_CANDIDATES` 常量已移除。
- 旧 `Math.min(500, Math.max(topK * 12, 120))` 逻辑已集中替换为 `resolveKeywordCandidateSize(topK)`。
- 新增配置项和解析方法已被测试覆盖。

## 四、边缘场景覆盖

| 场景 | 预期 | 覆盖情况 |
| --- | --- | --- |
| 未配置 keyword 候选窗口 | 保持历史窗口计算 | 已覆盖 |
| 配置合法窗口 | 使用配置值计算 | 已覆盖 |
| 配置为非数字、`0`、负数 | 回落默认值 | 已覆盖 |
| `topK <= 0` | 按 `topK=1` 处理 | 已覆盖 |

## 五、生产结论

本轮修复后，keyword 模式文档候选枚举窗口已具备运行期配置能力。生产环境可根据索引规模和关键词命中稀疏度调整候选规模，在召回质量和 ES 压力之间做可控权衡。

后续建议：

- 继续收口 `KeywordCoarseEvidenceStep` 的 `MAX_DISPLAY_COARSE=3`，让前端展示证据条数也可配置。
- 汇总 v2.0 过渡版全部新增运行参数，形成统一配置说明和推荐生产默认值。
