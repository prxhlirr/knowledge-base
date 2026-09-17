# 知识库升级 v2.0 过渡版 - VectorFetchStep 向量化超时配置化测试报告

## 一、任务目标

从第一性原理看，向量化链路是 ES 召回之前的前置依赖。若 HyDE 或 dense/sparse 向量获取等待时间写死，生产环境在模型延迟、AI 服务吞吐、网络抖动变化时无法快速调参，会直接影响搜索总 SLA。

本轮目标：

- 将文档类型查询专属 HyDE 等待时间配置化。
- 将文档类型查询原始 dense/sparse 向量等待时间配置化。
- 将普通长查询 HyDE 等待时间配置化。
- 将普通长查询原始 dense/sparse 向量等待时间配置化。
- 保持历史默认值不变，确保上线后默认行为兼容。

## 二、实施内容

### 1. 代码变更

文件：

- `java_service/src/main/java/com/boyang/search/pipeline/steps/VectorFetchStep.java`

新增配置项：

| 配置项 | 环境变量 | 默认值 | 作用 |
| --- | --- | ---: | --- |
| `search.vector.doc-type-hyde-timeout-ms` | `SEARCH_VECTOR_DOC_TYPE_HYDE_TIMEOUT_MS` | `4000` | 文档类型查询专属 HyDE 等待时间 |
| `search.vector.doc-type-dual-timeout-ms` | `SEARCH_VECTOR_DOC_TYPE_DUAL_TIMEOUT_MS` | `3000` | 文档类型查询原始 dense/sparse 向量等待时间 |
| `search.vector.hyde-timeout-ms` | `SEARCH_VECTOR_HYDE_TIMEOUT_MS` | `1000` | 普通长查询 HyDE 快速等待时间 |
| `search.vector.dual-timeout-ms` | `SEARCH_VECTOR_DUAL_TIMEOUT_MS` | `3000` | 普通长查询原始 dense/sparse 向量等待时间 |

关键规则：

- 默认值与历史硬编码保持一致。
- 配置为空、非数字、`<=0` 时自动回落默认值。
- 不改变 HyDE Gate、缓存、dense/sparse 写入、失败降级策略。

### 2. 测试变更

文件：

- `java_service/src/test/java/com/boyang/search/pipeline/steps/VectorFetchStepTimeoutConfigTest.java`

覆盖测试：

- `vectorTimeoutsUseDefaultValuesWhenNoConfigProvided`
- `vectorTimeoutsUseConfiguredPositiveValues`
- `vectorTimeoutsFallbackWhenConfiguredValuesAreInvalid`

## 三、执行验证

### 1. 单元测试

执行命令：

```bash
mvn -q -Dtest=VectorFetchStepTimeoutConfigTest test
```

执行结果：

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

结论：通过。

### 2. 静态核对

执行命令：

```bash
rg -n 'get\([0-9]_[0-9]{3}, TimeUnit\.MILLISECONDS\)|SEARCH_VECTOR_.*TIMEOUT|resolve.*Timeout|docType.*TimeoutMs|hydeTimeoutMs|dualTimeoutMs' java_service/src/main/java/com/boyang/search/pipeline/steps/VectorFetchStep.java java_service/src/test/java/com/boyang/search/pipeline/steps/VectorFetchStepTimeoutConfigTest.java
```

核对结论：

- 文档类型 HyDE 原 `4_000ms` 等待已替换为 `resolveDocTypeHydeTimeoutMs()`。
- 文档类型 dual vector 原 `3_000ms` 等待已替换为 `resolveDocTypeDualTimeoutMs()`。
- 普通 HyDE 原 `1_000ms` 等待已替换为 `resolveHydeTimeoutMs()`。
- 普通 dual vector 原 `3_000ms` 等待已替换为 `resolveDualTimeoutMs()`。

## 四、边缘场景覆盖

| 场景 | 预期 | 覆盖情况 |
| --- | --- | --- |
| 未注入配置 | 使用历史默认值 | 已覆盖 |
| 配置合法正整数 | 使用配置值 | 已覆盖 |
| 配置为 `0`、负数、空白、非数字 | 回落默认值 | 已覆盖 |

## 五、生产结论

本轮修复后，检索前置向量化链路的关键等待时间已具备运行期配置能力。生产环境可按模型响应耗时、AI 服务吞吐和搜索 SLA 独立调节，无需为这些参数重新发版。

后续建议：

- 继续收口 `SemanticRecallStrategy` 中独立语义召回的 ES 超时与 KNN 等待策略。
- 继续检查 `DocExpansionStep` 中 sibling/parent 查询等待时间，避免结果扩展阶段存在固定 3s 等待。
