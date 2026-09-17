# 知识库升级 v2.0 过渡版 - LiteralRecallStep 字面量检索超时配置化测试报告

## 一、任务目标

从第一性原理看，`LiteralRecallStep` 是高置信精确探测路径，主要面向文号、标题、来源等字面量命中。它的目标是快速短路，而不是长时间等待。因此固定 `1000ms` 虽然历史上合理，但在不同索引规模、节点负载和 SLA 下必须具备运行期调整能力。

本轮目标：

- 将字面量精确探测 ES 查询超时配置化。
- 保持历史默认值 1000ms 不变。
- 配置非法时自动回落默认值。
- 验证构造出的 `SearchRequest.timeout` 确实使用配置值。

## 二、实施内容

### 1. 代码变更

文件：

- `java_service/src/main/java/com/boyang/search/pipeline/steps/LiteralRecallStep.java`

新增配置项：

| 配置项 | 环境变量 | 默认值 | 作用 |
| --- | --- | ---: | --- |
| `search.literal.timeout-ms` | `SEARCH_LITERAL_TIMEOUT_MS` | `1000` | literal 精确元数据查询与标题短语查询超时 |

关键规则：

- 默认值保持历史 `1000ms` 行为。
- 配置为空、非数字、`<=0` 时自动回落 `1000ms`。
- 精确元数据查询和标题短语补偿查询共用同一个 literal 超时配置。
- 不改变权限过滤、最新版本过滤、命中短路和 keyword 延迟合并逻辑。

### 2. 测试变更

文件：

- `java_service/src/test/java/com/boyang/search/pipeline/steps/LiteralRecallStepTimeoutConfigTest.java`

覆盖测试：

- `literalTimeoutUsesDefaultValueWhenNoConfigProvided`
- `literalTimeoutUsesConfiguredPositiveValue`
- `literalTimeoutFallbackWhenConfiguredValueIsInvalid`
- `exactMetadataRequestUsesConfiguredLiteralTimeout`

## 三、执行验证

### 1. 单元测试

执行命令：

```bash
mvn -q -Dtest=LiteralRecallStepTimeoutConfigTest test
```

执行结果：

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

结论：通过。

### 2. 静态核对

执行命令：

```bash
rg -n 'LITERAL_TIMEOUT|timeout\("1000ms"\)|SEARCH_LITERAL_TIMEOUT_MS|literalTimeoutMs|resolveLiteralTimeoutMs|resolvePositiveTimeoutMs' java_service/src/main/java/com/boyang/search/pipeline/steps/LiteralRecallStep.java java_service/src/test/java/com/boyang/search/pipeline/steps/LiteralRecallStepTimeoutConfigTest.java
```

核对结论：

- 旧 `LITERAL_TIMEOUT` 常量已移除。
- 两处 `.timeout(...)` 均已替换为 `resolveLiteralTimeoutMs() + "ms"`。
- 新配置项和解析方法已被测试覆盖。

## 四、边缘场景覆盖

| 场景 | 预期 | 覆盖情况 |
| --- | --- | --- |
| 未配置 literal 超时 | 使用历史默认 1000ms | 已覆盖 |
| 配置合法正整数 | 使用配置值 | 已覆盖 |
| 配置为非数字、`0`、负数 | 回落 1000ms | 已覆盖 |
| 构造精确元数据查询请求 | `SearchRequest.timeout` 等于配置值 | 已覆盖 |

## 五、实施过程中的修正

首次测试断言假设 `SearchRequest.timeout()` 返回 `Time` 对象并调用 `.time()`，编译结果证明当前 ES Java Client 版本中该方法直接返回 `String`。已根据实际 API 修正为直接断言 `request.timeout()`，以代码事实为准。

## 六、生产结论

本轮修复后，字面量精确检索路径的 ES 查询超时已具备运行期配置能力。至此，前序已完成的 Hybrid、Semantic、VectorFetch、DocExpansion 与 Literal 主要等待参数都已从硬编码迁移为可配置入口。

后续建议：

- 汇总所有新增运行参数，生成 v2.0 过渡版统一配置说明。
- 扫描剩余 pipeline 和 service 层是否仍存在固定超时或固定容量参数。
