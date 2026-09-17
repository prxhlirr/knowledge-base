# 知识库升级 v2.0 过渡版 - 主检索总 SLA 超时配置化测试报告

## 任务目标

治理主检索总 SLA 超时默认值不一致的问题。

现状核查发现：

1. `application.yml` 中 `search.total.sla-ms` 已配置为 `10000`。
2. `SearchController` 中 `@Value("${search.total.sla-ms:5000}")` 仍保留 `5000` 默认值。
3. 当配置缺失时，Controller 会回退到 5s，与 v2.0 过渡版目标不一致。
4. 原字段类型为 `long`，如果环境变量配置成非数字，Spring 启动阶段会失败，无法兜底。

## 本轮改动

1. `java_service/src/main/java/com/boyang/search/controller/SearchController.java`
   - 新增 `DEFAULT_SEARCH_TOTAL_TIMEOUT_MS = 10000L`。
   - `searchTotalTimeoutMs` 从 `long` 改为 `String`。
   - `@Value` 改为：
     - `search.total.sla-ms`
     - 环境变量 `SEARCH_TOTAL_SLA_MS`
     - 默认 `10000`
   - 新增 `resolveSearchTotalTimeoutMs()`：
     - 空值回退 `10000`
     - 非数字回退 `10000`
     - `0/负数` 回退 `10000`
     - 正数按配置生效
   - `/search`、首页搜索、上下文搜索中的 `future.get(...)` 改为使用解析后的有效超时值。

2. `java_service/src/test/java/com/boyang/search/controller/SearchControllerTimeoutConfigTest.java`
   - 验证空值回退默认值。
   - 验证正数配置生效。
   - 验证非数字配置回退默认值。
   - 验证 `0/负数` 回退默认值。

3. `java_service/src/test/java/com/boyang/search/controller/SearchControllerQaPermissionProjectionTest.java`
   - 适配字段类型变更，将反射注入值从 `3000L` 改为 `"3000"`。

## 测试命令

```bash
cd java_service
mvn -q "-Dtest=SearchControllerTimeoutConfigTest,SearchControllerQaPermissionProjectionTest" test
```

补充扫描：

```bash
rg -n "search.total.sla-ms:5000|search.total.sla-ms|SEARCH_TOTAL_SLA_MS|resolveSearchTotalTimeoutMs|searchTotalTimeoutMs|5000ms|10000" java_service/src/main/java/com/boyang/search/controller/SearchController.java java_service/src/main/resources/application.yml java_service/src/test/java/com/boyang/search/controller
```

## 测试结果

```text
mvn -q "-Dtest=SearchControllerTimeoutConfigTest,SearchControllerQaPermissionProjectionTest" test
通过
```

扫描结论：

```text
SearchController 中旧的 search.total.sla-ms:5000 已清理
SearchController 默认值与 application.yml 均为 10000
支持 SEARCH_TOTAL_SLA_MS 环境变量
```

## 生产结论

1. 主检索总 SLA 默认值统一为 `10000ms`。
2. 生产可通过 `SEARCH_TOTAL_SLA_MS` 按环境调整，例如：
   - `SEARCH_TOTAL_SLA_MS=8000`
   - `SEARCH_TOTAL_SLA_MS=12000`
3. 非法配置不会导致服务启动失败，会回退 `10000ms`。
4. 本轮不改变 ES 查询、rerank 打分、权限过滤，仅治理总超时控制面。

## 剩余风险

1. 总 SLA 调大可能降低降级率，但会提高尾延迟和线程占用。
2. 总 SLA 调小可保护接口响应时间，但会提高 `sla_timeout_degraded` 比例。
3. 仍需结合 `search_audit_log`、ES slowlog、rerank 降级率和线程池占用做生产压测。
