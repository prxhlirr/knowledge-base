# 知识库升级 v2.0 过渡版 - DocExpansionStep 扩展等待配置化测试报告

## 一、任务目标

从第一性原理看，`DocExpansionStep` 是召回后的增强步骤，不是主召回本身。它的价值是补齐同文档 sibling chunk 与 fine→coarse 上下文，但固定等待 3 秒会让生产 SLA 难以治理：高负载时拖慢主链路，低负载时又无法按业务质量诉求放宽等待。

本轮目标：

- 将 Sibling/Parent 两路文档扩展查询的整体等待时间配置化。
- 保持历史默认值 3000ms 不变。
- 配置非法时自动回落默认值。
- 不改变扩展查询、结果追加、fine→coarse 回溯、权限前置结果的既有逻辑。

## 二、实施内容

### 1. 代码变更

文件：

- `java_service/src/main/java/com/boyang/search/pipeline/steps/DocExpansionStep.java`

新增配置项：

| 配置项 | 环境变量 | 默认值 | 作用 |
| --- | --- | ---: | --- |
| `search.doc-expansion.timeout-ms` | `SEARCH_DOC_EXPANSION_TIMEOUT_MS` | `3000` | Sibling/Parent 两路扩展查询整体等待时间 |

关键规则：

- 默认值保持历史 3 秒行为。
- 配置为空、非数字、`<=0` 时自动回落 3000ms。
- 超时后仍取消两路 Future，并使用已有候选池降级继续。

### 2. 测试变更

文件：

- `java_service/src/test/java/com/boyang/search/pipeline/steps/DocExpansionStepTimeoutConfigTest.java`

覆盖测试：

- `docExpansionTimeoutUsesDefaultValueWhenNoConfigProvided`
- `docExpansionTimeoutUsesConfiguredPositiveValue`
- `docExpansionTimeoutFallbackWhenConfiguredValueIsInvalid`

## 三、执行验证

### 1. 单元测试

执行命令：

```bash
mvn -q -Dtest=DocExpansionStepTimeoutConfigTest test
```

执行结果：

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

结论：通过。

### 2. 静态核对

执行命令：

```bash
rg -n 'get\(3, TimeUnit\.SECONDS\)|SEARCH_DOC_EXPANSION_TIMEOUT_MS|docExpansionTimeoutMs|resolveDocExpansionTimeoutMs|resolvePositiveTimeoutMs' java_service/src/main/java/com/boyang/search/pipeline/steps/DocExpansionStep.java java_service/src/test/java/com/boyang/search/pipeline/steps/DocExpansionStepTimeoutConfigTest.java
```

核对结论：

- 原固定 `get(3, TimeUnit.SECONDS)` 已替换为 `get(resolveDocExpansionTimeoutMs(), TimeUnit.MILLISECONDS)`。
- 新增配置入口和解析方法已被测试覆盖。

## 四、边缘场景覆盖

| 场景 | 预期 | 覆盖情况 |
| --- | --- | --- |
| 未配置扩展等待时间 | 使用历史默认 3000ms | 已覆盖 |
| 配置合法正整数 | 使用配置值 | 已覆盖 |
| 配置为非数字、`0`、负数 | 回落 3000ms | 已覆盖 |

## 五、生产结论

本轮修复后，文档扩展阶段的等待时间已具备运行期配置能力。生产环境可根据搜索 SLA 和上下文补齐质量要求动态调节，不再需要为固定 3 秒等待重新发版。

后续建议：

- 继续检查 `LiteralRecallStep` 的字面量检索超时是否需要同样配置化。
- 继续汇总所有新增运行参数，形成统一的 v2.0 过渡版配置说明。
