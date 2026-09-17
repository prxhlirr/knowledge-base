# 知识库升级 v2.0 过渡版 - ColBERT Rerank 超时配置化测试报告

## 任务目标

治理 `RerankStep` 中 ColBERT 调用超时时间 `6000/8000ms` 的硬编码问题。

该超时直接影响 rerank 吞吐、降级比例和查询 SLA。v2.0 过渡版保留默认值，但支持按部署环境调优。

## 本轮改动

1. `java_service/src/main/java/com/boyang/search/pipeline/steps/RerankStep.java`
   - 新增普通 ColBERT 超时配置：
     - Spring 配置项：`ai.service.colbert-timeout-ms`
     - 环境变量：`AI_COLBERT_TIMEOUT_MS`
     - 默认值：`6000`
   - 新增语义强制 rerank 超时配置：
     - Spring 配置项：`ai.service.colbert-semantic-timeout-ms`
     - 环境变量：`AI_COLBERT_SEMANTIC_TIMEOUT_MS`
     - 默认值：`8000`
   - 新增 `normalizeColbertTimeoutMs(long configured, long defaultValue)`。
   - 新增 `resolveColbertTimeoutMs(boolean forceRerankForSemantics)`。
   - ColBERT `CompletableFuture.get(...)` 和超时日志均改为使用统一解析结果。

2. `java_service/src/test/java/com/boyang/search/pipeline/steps/RerankStepColbertConcurrencyTest.java`
   - 在已有 ColBERT 并发测试中追加超时测试：
     - 正数配置保持原值。
     - `0/负数` 回退默认值。
     - 普通 rerank 默认 `6000ms`。
     - 语义强制 rerank 默认 `8000ms`。
     - 配置覆盖值可生效。

## 测试命令

```bash
cd java_service
mvn -q -Dtest=RerankStepColbertConcurrencyTest test
```

补充扫描：

```bash
rg -n "6000L|8000L|6000|8000|colbert-timeout-ms|AI_COLBERT_TIMEOUT_MS|colbert-semantic-timeout-ms|AI_COLBERT_SEMANTIC_TIMEOUT_MS|resolveColbertTimeoutMs" java_service/src/main/java/com/boyang/search/pipeline/steps/RerankStep.java java_service/src/test/java/com/boyang/search/pipeline/steps/RerankStepColbertConcurrencyTest.java
```

## 测试结果

```text
mvn -q -Dtest=RerankStepColbertConcurrencyTest test
通过
```

扫描结论：

```text
实际调用处使用 resolveColbertTimeoutMs(...)
6000/8000 仅作为默认兜底值和测试断言存在
```

## 生产结论

1. 默认行为保持不变：
   - 普通 ColBERT rerank：`6000ms`
   - 语义强制 rerank：`8000ms`
2. 生产可通过环境变量调整：
   - `AI_COLBERT_TIMEOUT_MS=4500`
   - `AI_COLBERT_SEMANTIC_TIMEOUT_MS=9500`
3. 错误配置为 `0` 或负数时自动回退默认值，不会造成立即超时或无限等待。
4. 本轮不改变 rerank 打分、输入窗口、ES 检索逻辑。

## 剩余风险

1. 超时调大可能提升召回质量，但会提高接口尾延迟和线程占用，需要压测。
2. 超时调小可降低 SLA 风险，但会增加 rerank 降级率。
3. 后续仍需结合 `rerank_top_k`、`rerank_global_max_chars`、ColBERT GPU 利用率和 `search_audit_log.rerank_degraded` 做容量调优。
