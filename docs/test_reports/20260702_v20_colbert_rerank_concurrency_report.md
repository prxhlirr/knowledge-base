# 知识库升级 v2.0 过渡版 - ColBERT Rerank 并发配置化测试报告

## 任务目标

治理 `RerankStep` 中 `COLBERT_SEM_MAX=4` 静态硬编码问题。

该限制会成为高 QPS 场景下 rerank 吞吐上限。v2.0 过渡版不直接移除背压，而是保留默认值并支持环境变量/配置项动态调整。

## 本轮改动

1. `java_service/src/main/java/com/boyang/search/pipeline/steps/RerankStep.java`
   - 删除静态常量 `COLBERT_SEM_MAX`。
   - 删除静态信号量 `COLBERT_SEM`。
   - 新增配置：
     - Spring 配置项：`ai.service.colbert-max-concurrency`
     - 环境变量：`AI_COLBERT_MAX_CONCURRENCY`
     - 默认值：`4`
   - 新增 `normalizeColbertMaxConcurrency(int configured)`。
   - 新增 `initColbertSemaphore()`，在 Spring 初始化后按配置重建实例级 Semaphore。
   - ColBERT acquire/release 改为使用实例级 `colbertSemaphore`。
   - 降级日志改为输出当前生效并发上限。

2. `java_service/src/test/java/com/boyang/search/pipeline/steps/RerankStepColbertConcurrencyTest.java`
   - 验证正数配置保持原值。
   - 验证 `0/负数` 回退默认 `4`。
   - 验证初始化后配置值生效。
   - 验证非法配置初始化后回退默认值。

## 测试命令

```bash
cd java_service
mvn -q -Dtest=RerankStepColbertConcurrencyTest test
```

补充扫描：

```bash
rg -n "COLBERT_SEM_MAX|COLBERT_SEM|colbert-max-concurrency|AI_COLBERT_MAX_CONCURRENCY|configuredColbertMaxConcurrency|colbertSemaphore" java_service/src/main/java/com/boyang/search/pipeline/steps/RerankStep.java java_service/src/test/java/com/boyang/search/pipeline/steps/RerankStepColbertConcurrencyTest.java
```

## 测试结果

```text
mvn -q -Dtest=RerankStepColbertConcurrencyTest test
通过
```

扫描结论：

```text
COLBERT_SEM_MAX 不再存在
COLBERT_SEM 不再存在
RerankStep 使用 ai.service.colbert-max-concurrency / AI_COLBERT_MAX_CONCURRENCY
```

## 生产结论

1. 默认行为仍是 ColBERT 最大并发 `4`，兼容现有生产保守配置。
2. 高 QPS 或独立 GPU Worker 场景可通过 `AI_COLBERT_MAX_CONCURRENCY=6/8` 调整吞吐。
3. 错误配置为 `0` 或负数时会回退 `4`，不会关闭背压。
4. 本轮不改变 rerank 输入窗口、超时、打分逻辑，也不改变 ES 查询。

## 剩余风险

1. 并发调大只提升吞吐上限，可能增加 GPU 显存和排队压力，必须配合压测。
2. 当前 ColBERT 超时时间仍固定在 `6000/8000ms` 分支中，后续可继续配置化。
3. Rerank 输入窗口和字符预算虽然已有数据库字段，但仍需结合亿级高 QPS 做容量压测。
