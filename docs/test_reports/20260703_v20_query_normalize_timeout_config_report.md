# 知识库升级 v2.0 过渡版 - QueryNormalizeStep 归一化等待配置化测试报告

## 一、任务目标

从第一性原理看，`QueryNormalizeStep` 是检索入口步骤。它在召回、权限过滤、重排之前执行，因此任何固定等待都会直接进入搜索总 SLA。原逻辑对 Python NLP normalize 固定等待 3 秒，生产环境无法根据 AI 服务延迟和搜索 SLA 动态调节。

本轮目标：

- 将 Python NLP normalize 等待时间配置化。
- 保持历史默认值 3000ms 不变。
- 配置非法时自动回落默认值。
- normalize 异常或超时时继续沿用本地全角转半角降级。

## 二、实施内容

### 1. 代码变更

文件：

- `java_service/src/main/java/com/boyang/search/pipeline/steps/QueryNormalizeStep.java`

新增配置项：

| 配置项 | 环境变量 | 默认值 | 作用 |
| --- | --- | ---: | --- |
| `search.query-normalize.timeout-ms` | `SEARCH_QUERY_NORMALIZE_TIMEOUT_MS` | `3000` | Python NLP normalize 等待时间 |

关键规则：

- 默认值保持历史 3 秒行为。
- 配置为空、非数字、`<=0` 时自动回落 3000ms。
- 超时或异常时仍走本地 normalize fallback。
- 超时或异常后取消未完成的 `normFuture`，避免后台任务继续占用资源。
- keyword、精确查询、语义问句跳过 NLP 的既有逻辑保持不变。

### 2. 测试变更

文件：

- `java_service/src/test/java/com/boyang/search/pipeline/steps/QueryNormalizeStepTimeoutConfigTest.java`

覆盖测试：

- `queryNormalizeTimeoutUsesDefaultValueWhenNoConfigProvided`
- `queryNormalizeTimeoutUsesConfiguredPositiveValue`
- `queryNormalizeTimeoutFallbackWhenConfiguredValueIsInvalid`

## 三、执行验证

### 1. 首次测试

执行命令：

```bash
mvn -q -Dtest=QueryNormalizeStepTimeoutConfigTest test
```

执行结果：

```text
Surefire forked VM terminated without properly saying goodbye.
dumpstream: Error occurred during initialization of VM
```

结论：未进入 JUnit 断言阶段，属于 Surefire fork JVM 启动异常，不是测试用例失败。

### 2. 非 fork 模式复测

执行命令：

```bash
mvn -q -Dtest=QueryNormalizeStepTimeoutConfigTest -DforkCount=0 test
```

执行结果：

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

结论：通过。

### 3. 静态核对

执行命令：

```bash
rg -n 'get\(3, TimeUnit\.SECONDS\)|SEARCH_QUERY_NORMALIZE_TIMEOUT_MS|queryNormalizeTimeoutMs|resolveQueryNormalizeTimeoutMs|resolvePositiveTimeoutMs|normFuture.cancel' java_service/src/main/java/com/boyang/search/pipeline/steps/QueryNormalizeStep.java java_service/src/test/java/com/boyang/search/pipeline/steps/QueryNormalizeStepTimeoutConfigTest.java
```

核对结论：

- 原固定 `normFuture.get(3, TimeUnit.SECONDS)` 已替换为 `normFuture.get(resolveQueryNormalizeTimeoutMs(), TimeUnit.MILLISECONDS)`。
- 新增配置入口和解析方法已被测试覆盖。
- 异常降级分支已增加 `normFuture.cancel(true)`。

## 四、边缘场景覆盖

| 场景 | 预期 | 覆盖情况 |
| --- | --- | --- |
| 未配置 normalize 等待时间 | 使用历史默认 3000ms | 已覆盖 |
| 配置合法正整数 | 使用配置值 | 已覆盖 |
| 配置为非数字、`0`、负数 | 回落 3000ms | 已覆盖 |

## 五、生产结论

本轮修复后，检索入口归一化步骤的外部 NLP 等待时间已具备运行期配置能力。生产环境可根据 AI 服务稳定性、业务模式和总 SLA 调整等待预算，不再需要为固定 3 秒等待重新发版。

后续建议：

- 继续收口 `SearchServiceV2` 中 `vectorFuture.get(6000ms)` 的固定等待。
- 汇总 v2.0 过渡版新增配置项，形成统一配置说明。
