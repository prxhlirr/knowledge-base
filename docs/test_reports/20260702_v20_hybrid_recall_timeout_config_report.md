# 知识库升级 v2.0 过渡版 - Hybrid 召回局部超时配置化测试报告

## 一、任务目标

从第一性原理看，亿级检索下超时值不是业务常量，而是容量治理参数。`HybridRecallStrategy` 是默认混合召回主链路，若 Pre-Flight、Sparse、SubQuery 等局部超时写死，生产环境在索引规模、节点规格、QPS 变化时只能通过发版调整，无法快速治理延迟和资源占用。

本轮目标：

- 将 Hybrid Pre-Flight 精准探测超时从硬编码改为配置。
- 将 Hybrid 主召回 ES 默认超时兜底从硬编码改为配置，且保留数据库调优配置优先级。
- 将 Hybrid Sparse 召回超时从硬编码改为配置。
- 将 SubQuery 并行扩展等待时间从硬编码改为配置。
- 覆盖默认值、配置覆盖、非法值兜底、数据库调优优先级测试。

## 二、实施内容

### 1. 代码变更

文件：

- `java_service/src/main/java/com/boyang/search/pipeline/steps/HybridRecallStrategy.java`

新增配置项：

| 配置项 | 环境变量 | 默认值 | 作用 |
| --- | --- | ---: | --- |
| `search.hybrid.preflight-timeout-ms` | `SEARCH_HYBRID_PREFLIGHT_TIMEOUT_MS` | `1000` | Pre-Flight 精准短路探测超时 |
| `search.hybrid.es-timeout-ms` | `SEARCH_HYBRID_ES_TIMEOUT_MS` | `2000` | 主召回 ES 查询默认超时兜底 |
| `search.hybrid.sparse-timeout-ms` | `SEARCH_HYBRID_SPARSE_TIMEOUT_MS` | `2000` | Sparse 召回 ES 查询超时 |
| `search.hybrid.sub-query-timeout-ms` | `SEARCH_HYBRID_SUB_QUERY_TIMEOUT_MS` | `5000` | SubQuery 并行扩展整体等待时间 |

关键规则：

- `SysAiTuningConfig.esQueryTimeout` 仍然优先于 `search.hybrid.es-timeout-ms`。
- 配置值为空、非数字、`<=0` 时自动回落默认值。
- 不改变原有召回分支的启停逻辑、权限过滤逻辑、候选过滤逻辑。

### 2. 测试变更

文件：

- `java_service/src/test/java/com/boyang/search/pipeline/steps/HybridRecallStrategyPrefilterConditionTest.java`

新增测试：

- `hybridTimeoutsUseDefaultValuesWhenNoConfigProvided`
- `hybridTimeoutsUseConfiguredPositiveValues`
- `hybridTimeoutsFallbackWhenConfiguredValuesAreInvalid`
- `esTimeoutPrefersDatabaseTuningConfig`

## 三、执行验证

### 1. 单元测试

执行命令：

```bash
mvn -q -Dtest=HybridRecallStrategyPrefilterConditionTest test
```

执行结果：

```text
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
```

结论：通过。

### 2. 静态核对

执行命令：

```bash
rg -n 'timeout\("[0-9]+ms"\)|get\(5_000, TimeUnit\.MILLISECONDS\)|SEARCH_HYBRID_.*TIMEOUT|resolve.*Timeout|getEsTimeoutMs|hybrid.*TimeoutMs' java_service/src/main/java/com/boyang/search/pipeline/steps/HybridRecallStrategy.java java_service/src/test/java/com/boyang/search/pipeline/steps/HybridRecallStrategyPrefilterConditionTest.java
```

核对结论：

- 原 `timeout("1000ms")` 已替换为 `resolvePreflightTimeoutMs() + "ms"`。
- 原 `timeout("2000ms")` 已替换为 `resolveSparseTimeoutMs() + "ms"`。
- 原 `get(5_000, TimeUnit.MILLISECONDS)` 已替换为 `get(resolveSubQueryTimeoutMs(), TimeUnit.MILLISECONDS)`。
- 主召回 ES 超时兜底由 `resolvePositiveTimeoutMs(hybridEsTimeoutMs, 2000)` 解析。

## 四、边缘场景覆盖

| 场景 | 预期 | 覆盖情况 |
| --- | --- | --- |
| 未配置任何 Hybrid 局部超时 | 使用历史默认值，行为不变 | 已覆盖 |
| 配置合法正整数 | 使用配置值 | 已覆盖 |
| 配置为 `0`、负数、空白、非数字 | 回落默认值 | 已覆盖 |
| 数据库调优表配置 `esQueryTimeout` | 优先使用数据库值 | 已覆盖 |

## 五、生产结论

本轮修复后，Hybrid 主召回链路的关键局部超时已经具备运行期配置能力，可在不同索引规模和生产负载下独立调节，不再需要为这些参数改代码发版。

本轮未处理但仍建议后续推进：

- `VectorFetchStep` 中 HyDE 相关向量生成和重写超时仍存在局部硬编码，需要下一轮单独收口。
- `SemanticRecallStrategy` 中独立语义召回超时仍应按同样模式配置化。
- 后续可把这些运行参数沉淀到统一配置文档，便于运维按环境覆盖。
