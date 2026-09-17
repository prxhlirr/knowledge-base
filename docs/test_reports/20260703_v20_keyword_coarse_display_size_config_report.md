# 知识库升级 v2.0 过渡版 - KeywordCoarseEvidenceStep 展示证据条数配置化测试报告

## 一、任务目标

从第一性原理看，keyword 模式分两层控制：

- `KeywordRecallStrategy` 控制“每个关键词枚举多少文档候选”。
- `KeywordCoarseEvidenceStep` 控制“每个命中文档展示多少条 coarse 证据”。

上一轮已将文档候选窗口配置化。本轮继续收口 `KeywordCoarseEvidenceStep` 中固定的 `MAX_DISPLAY_COARSE=3`，让前端展示证据密度、回表 size 和响应体大小都具备运行期治理能力。

## 二、实施内容

### 1. 代码变更

文件：

- `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordCoarseEvidenceStep.java`

新增配置项：

| 配置项 | 环境变量 | 默认值 | 作用 |
| --- | --- | ---: | --- |
| `search.keyword.coarse.display-size` | `SEARCH_KEYWORD_COARSE_DISPLAY_SIZE` | `3` | 每个 keyword 命中文档最多展示的 coarse 证据条数 |

关键规则：

- 默认值保持历史展示 3 条证据行为。
- 配置为空、非数字、`<=0` 时自动回落 3。
- `matching-size` 继续控制候选回表余量。
- `display-size` 只控制最终展示上限和 fallback 查询 size。
- 批量 fallback 查询、单文档 fallback 查询、`chooseCoverageChunks` 选择逻辑均使用同一配置。

## 三、测试变更

文件：

- `java_service/src/test/java/com/boyang/search/pipeline/steps/KeywordCoarseEvidenceDisplaySizeConfigTest.java`

覆盖测试：

- `displayCoarseSizeUsesDefaultValueWhenNoConfigProvided`
- `displayCoarseSizeUsesConfiguredPositiveValue`
- `displayCoarseSizeFallbackWhenConfiguredValueIsInvalid`
- `chooseCoverageChunksHonorsConfiguredDisplayLimit`

## 四、执行验证

### 1. 单元测试

执行命令：

```bash
mvn -q -Dtest=KeywordCoarseEvidenceDisplaySizeConfigTest -DforkCount=0 test
```

执行结果：

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

结论：通过。

### 2. 静态核对

执行命令：

```bash
rg -n 'MAX_DISPLAY_COARSE|SEARCH_KEYWORD_COARSE_DISPLAY_SIZE|displayCoarseSize|resolveDisplayCoarseSize|resolvePositiveInt|chosen\.size\(\) <|chosen\.size\(\) >=' java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordCoarseEvidenceStep.java java_service/src/test/java/com/boyang/search/pipeline/steps/KeywordCoarseEvidenceDisplaySizeConfigTest.java
```

核对结论：

- 旧 `MAX_DISPLAY_COARSE` 常量已移除。
- fallback 查询 size 已改为 `resolveDisplayCoarseSize()`。
- `chooseCoverageChunks` 的选择上限已改为配置值。

## 五、边缘场景覆盖

| 场景 | 预期 | 覆盖情况 |
| --- | --- | --- |
| 未配置展示条数 | 使用历史默认 3 | 已覆盖 |
| 配置合法正整数 | 使用配置值 | 已覆盖 |
| 配置为非数字、`0`、负数 | 回落 3 | 已覆盖 |
| 实际选择证据条数 | 不超过配置上限 | 已覆盖 |

## 六、生产结论

本轮修复后，keyword 模式的“文档候选窗口”和“每文档展示证据条数”都已具备运行期配置能力。生产环境可以独立调整召回候选规模和结果展示密度，避免为了 UI 证据条数或响应体大小重新发版。

后续建议：

- 汇总 v2.0 过渡版所有新增配置项，形成统一配置说明。
- 继续扫描 `SearchController`、`SysSearchTagServiceImpl` 等非主 pipeline 中固定 size 是否属于生产检索路径。
