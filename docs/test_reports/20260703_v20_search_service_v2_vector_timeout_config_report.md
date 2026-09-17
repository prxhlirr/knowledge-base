# 知识库升级 v2.0 过渡版 - SearchServiceV2 向量并行轨道等待配置化测试报告

## 一、任务目标

从第一性原理看，`SearchServiceV2` 是检索编排层。它在 Phase 2 中并行执行 `VectorFetchStep`、`LiteralRecallStep` 和 `DocSearchPrefilter`，原逻辑固定等待 `vectorFuture.get(6000ms)`。即使 `VectorFetchStep` 内部超时已配置化，外层编排等待仍固定，会限制生产环境对入口延迟的统一治理。

本轮目标：

- 将 V2 并行轨道中 `VectorFetchStep` 的整体等待预算配置化。
- 保持历史默认值 6000ms 不变。
- 配置非法时自动回落默认值。
- 不改变 VectorFetch、LiteralRecall、DocSearchPrefilter 的执行顺序和降级语义。

## 二、实施内容

### 1. 代码变更

文件：

- `java_service/src/main/java/com/boyang/search/service/SearchServiceV2.java`

新增配置项：

| 配置项 | 环境变量 | 默认值 | 作用 |
| --- | --- | ---: | --- |
| `search.v2.vector-fetch-timeout-ms` | `SEARCH_V2_VECTOR_FETCH_TIMEOUT_MS` | `6000` | V2 并行轨道等待 `VectorFetchStep` 完成的整体预算 |

关键规则：

- 默认值保持历史 6000ms 行为。
- 配置为空、非数字、`<=0` 时自动回落 6000ms。
- 超时或异常时仍取消 `vectorFuture`，并继续降级召回。

### 2. 测试变更

文件：

- `java_service/src/test/java/com/boyang/search/service/SearchServiceV2TimeoutConfigTest.java`

覆盖测试：

- `vectorFetchTimeoutUsesDefaultValueWhenNoConfigProvided`
- `vectorFetchTimeoutUsesConfiguredPositiveValue`
- `vectorFetchTimeoutFallbackWhenConfiguredValueIsInvalid`

## 三、执行验证

### 1. 单元测试

执行命令：

```bash
mvn -q -Dtest=SearchServiceV2TimeoutConfigTest -DforkCount=0 test
```

执行结果：

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

结论：通过。

### 2. 静态核对

执行命令：

```bash
rg -n 'get\(6_000, TimeUnit\.MILLISECONDS\)|SEARCH_V2_VECTOR_FETCH_TIMEOUT_MS|vectorFetchTimeoutMs|resolveVectorFetchTimeoutMs|resolvePositiveTimeoutMs' java_service/src/main/java/com/boyang/search/service/SearchServiceV2.java java_service/src/test/java/com/boyang/search/service/SearchServiceV2TimeoutConfigTest.java
```

核对结论：

- 原固定 `vectorFuture.get(6_000, TimeUnit.MILLISECONDS)` 已替换为 `vectorFuture.get(resolveVectorFetchTimeoutMs(), TimeUnit.MILLISECONDS)`。
- 新增配置入口和解析方法已被测试覆盖。

## 四、边缘场景覆盖

| 场景 | 预期 | 覆盖情况 |
| --- | --- | --- |
| 未配置 V2 向量等待预算 | 使用历史默认 6000ms | 已覆盖 |
| 配置合法正整数 | 使用配置值 | 已覆盖 |
| 配置为非数字、`0`、负数 | 回落 6000ms | 已覆盖 |

## 五、生产结论

本轮修复后，V2 编排层对向量化并行轨道的外层等待预算已经具备运行期配置能力。它与前序 `VectorFetchStep` 内部 HyDE/dual-vector 超时配置形成两层治理：内部控制具体 AI 请求等待，外层控制整个向量轨道对主搜索链路的最大影响。

后续建议：

- 汇总 v2.0 过渡版所有新增配置项，形成统一配置说明。
- 继续扫描 `KeywordCoarseEvidenceStep`、`KeywordRecallStrategy` 中固定候选窗口和固定 size 是否需要配置化。
