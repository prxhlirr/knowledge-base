# 知识库升级 v2.0 过渡版 - AI Gateway HTTP 超时配置化测试报告

## 任务目标

治理 `AiEngineGateway` 中多个 `RestTemplate` 的 HTTP 连接/读取超时硬编码问题。

这些超时影响 embedding、rewrite、HyDE、sparse、ColBERT、LTR、LLM rerank 等链路的降级边界。v2.0 过渡版保留默认值，但支持按部署环境独立调整。

## 本轮改动

1. `java_service/src/main/java/com/boyang/search/gateway/AiEngineGateway.java`
   - 新增统一解析函数 `resolveTimeoutMs(String configured, int defaultValue)`。
   - 新增统一工厂方法 `requestFactory(...)`。
   - 将 `init()` 中所有直接 `setConnectTimeout(数字)` / `setReadTimeout(数字)` 改为配置解析。

2. 新增可配置项：
   - `ai.service.default-connect-timeout-ms` / `AI_DEFAULT_CONNECT_TIMEOUT_MS`，默认 `3000`
   - `ai.service.default-read-timeout-ms` / `AI_DEFAULT_READ_TIMEOUT_MS`，默认 `15000`
   - `ai.service.fast-connect-timeout-ms` / `AI_FAST_CONNECT_TIMEOUT_MS`，默认 `3000`
   - `ai.service.fast-read-timeout-ms` / `AI_FAST_READ_TIMEOUT_MS`，默认 `5000`
   - `ai.service.llm-connect-timeout-ms` / `AI_LLM_CONNECT_TIMEOUT_MS`，默认 `5000`
   - `ai.service.llm-read-timeout-ms` / `AI_LLM_READ_TIMEOUT_MS`，默认 `60000`
   - `ai.service.ltr-connect-timeout-ms` / `AI_LTR_CONNECT_TIMEOUT_MS`，默认 `2000`
   - `ai.service.ltr-read-timeout-ms` / `AI_LTR_READ_TIMEOUT_MS`，默认 `5000`
   - `ai.service.colbert-connect-timeout-ms` / `AI_COLBERT_CONNECT_TIMEOUT_MS`，默认 `2000`
   - `ai.service.colbert-read-timeout-ms` / `AI_COLBERT_READ_TIMEOUT_MS`，默认 `5000`
   - `ai.service.sparse-connect-timeout-ms` / `AI_SPARSE_CONNECT_TIMEOUT_MS`，默认 `1500`
   - `ai.service.sparse-read-timeout-ms` / `AI_SPARSE_READ_TIMEOUT_MS`，默认 `1500`

3. `java_service/src/test/java/com/boyang/search/gateway/AiEngineGatewayTimeoutConfigTest.java`
   - 验证空值回退默认值。
   - 验证正数配置生效。
   - 验证非数字配置回退默认值。
   - 验证 `0/负数` 回退默认值。

## 测试命令

```bash
cd java_service
mvn -q -Dtest=AiEngineGatewayTimeoutConfigTest test
```

补充扫描：

```bash
rg -n "setConnectTimeout\\([0-9]|setReadTimeout\\([0-9]|AI_.*TIMEOUT_MS|default-connect-timeout-ms|fast-read-timeout-ms|colbert-read-timeout-ms|resolveTimeoutMs|requestFactory" java_service/src/main/java/com/boyang/search/gateway/AiEngineGateway.java java_service/src/test/java/com/boyang/search/gateway/AiEngineGatewayTimeoutConfigTest.java
```

## 测试结果

```text
mvn -q -Dtest=AiEngineGatewayTimeoutConfigTest test
通过
```

扫描结论：

```text
AiEngineGateway.init() 中已无直接 setConnectTimeout(数字) / setReadTimeout(数字)
所有 RestTemplate 超时均通过配置解析函数生成
```

## 生产结论

1. 默认行为保持不变。
2. 生产可按 AI 能力通道分别调优超时。
3. 非法配置不会导致服务启动失败，会回退默认值。
4. 本轮不改变请求 URL、请求体、降级逻辑和业务返回结构。

## 剩余风险

1. 超时调大可能降低 AI 调用失败率，但会提高接口尾延迟和线程占用。
2. 超时调小可保护 SLA，但会提高 embedding/rerank/QA 降级概率。
3. 仍需结合 `search_audit_log`、AI 服务日志和网关错误率做生产压测。
