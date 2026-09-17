# 知识库升级 v2.0 过渡版 - SemanticRecallStrategy 超时配置化测试报告

## 一、任务目标

从第一性原理看，semantic 模式是独立于 hybrid 的另一条检索主路径。前序任务已将 Hybrid、向量化和网关超时配置化；如果 semantic 仍保留固定超时，会在生产环境形成不可调的慢路径，并且在调参配置缺失时存在空指针风险。

本轮目标：

- 将 semantic Sparse 召回 ES 超时配置化。
- 将 semantic BM25 兜底召回 ES 超时配置化。
- 将 semantic 四路召回整体等待超时配置化。
- 保留 `SysAiTuningConfig.esQueryTimeout` 数据库调优优先级。
- 为 `SysAiTuningConfig` 缺失场景提供兜底，避免 semantic 链路空指针。

## 二、实施内容

### 1. 代码变更

文件：

- `java_service/src/main/java/com/boyang/search/pipeline/steps/SemanticRecallStrategy.java`

新增配置项：

| 配置项 | 环境变量 | 默认值 | 作用 |
| --- | --- | ---: | --- |
| `search.semantic.sparse-timeout-ms` | `SEARCH_SEMANTIC_SPARSE_TIMEOUT_MS` | `2000` | semantic Sparse 召回 ES 查询超时 |
| `search.semantic.bm25-timeout-ms` | `SEARCH_SEMANTIC_BM25_TIMEOUT_MS` | `2000` | semantic BM25 兜底 ES 查询超时 |
| `search.semantic.es-timeout-ms` | `SEARCH_SEMANTIC_ES_TIMEOUT_MS` | `2000` | semantic 四路召回整体等待默认兜底 |

关键规则：

- `SysAiTuningConfig.esQueryTimeout` 仍优先于 `search.semantic.es-timeout-ms`。
- 配置为空、非数字、`<=0` 时自动回落默认值。
- `context.getTuningConfig()` 为空时使用 `new SysAiTuningConfig()` 兜底，保证 KNN 候选窗口和噪声词处理仍可执行。
- 不改变 KNN、Sparse、BM25、QA 降级结构，不改变权限过滤逻辑。

### 2. 测试变更

文件：

- `java_service/src/test/java/com/boyang/search/pipeline/steps/SemanticRecallStrategyTimeoutConfigTest.java`

覆盖测试：

- `semanticTimeoutsUseDefaultValuesWhenNoConfigProvided`
- `semanticTimeoutsUseConfiguredPositiveValues`
- `semanticTimeoutsFallbackWhenConfiguredValuesAreInvalid`
- `semanticEsTimeoutPrefersDatabaseTuningConfig`

## 三、执行验证

### 1. 单元测试

执行命令：

```bash
mvn -q -Dtest=SemanticRecallStrategyTimeoutConfigTest test
```

执行结果：

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

结论：通过。

### 2. 静态核对

执行命令：

```bash
rg -n 'timeout\("2000ms"\)|get\(config\.getEsQueryTimeout\(\), TimeUnit\.MILLISECONDS\)|SEARCH_SEMANTIC_.*TIMEOUT|resolve.*Timeout|semantic.*TimeoutMs|effectiveConfig' java_service/src/main/java/com/boyang/search/pipeline/steps/SemanticRecallStrategy.java java_service/src/test/java/com/boyang/search/pipeline/steps/SemanticRecallStrategyTimeoutConfigTest.java
```

核对结论：

- Sparse 原固定 `timeout("2000ms")` 已替换为 `resolveSparseTimeoutMs()`。
- BM25 兜底原固定 `timeout("2000ms")` 已替换为 `resolveBm25TimeoutMs()`。
- 四路等待原 `config.getEsQueryTimeout()` 已替换为 `resolveEsTimeoutMs(config)`。
- KNN 候选窗口和噪声词处理已使用 `effectiveConfig`，避免配置为空时异常。

## 四、边缘场景覆盖

| 场景 | 预期 | 覆盖情况 |
| --- | --- | --- |
| 未配置 semantic 超时 | 使用历史默认值 | 已覆盖 |
| 配置合法正整数 | 使用配置值 | 已覆盖 |
| 配置为 `0`、负数、非数字 | 回落默认值 | 已覆盖 |
| 数据库调优表配置 `esQueryTimeout` | 优先使用数据库值 | 已覆盖 |
| 调参配置为空 | 使用默认调参对象兜底 | 代码已覆盖 |

## 五、生产结论

本轮修复后，semantic 检索路径的关键 ES 等待参数已经具备运行期配置能力，并补齐了调参配置为空时的稳定性兜底。至此，Hybrid、Semantic、向量化和网关侧的主要等待参数已基本从硬编码迁移为可配置参数。

后续建议：

- 继续收口 `DocExpansionStep` 中 sibling/parent 扩展固定 3s 等待。
- 继续检查 `LiteralRecallStep` 的字面量检索超时是否已具备环境级配置入口。
