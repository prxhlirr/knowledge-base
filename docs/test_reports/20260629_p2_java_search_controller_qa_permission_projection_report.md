# P2 Java `/search` QA 权限字段透传接口级测试报告

## 1. 本轮任务

继续执行 ES 权限优化实施计划中的 Java `/search` 接口级验证。

本轮目标：确认 QA 命中结果经过 `SearchController.search(...)` 分页封装、缓存前置判断、AppCode 校验、HTTP JSON 序列化之后，最终响应 `data.list` 中仍保留权限投影字段：

- `source_index`
- `index_code`
- `owner_unit_code`
- `visible_unit_codes`

## 2. 代码事实验证

已验证 `/api/v1/search` 对应入口为：

- `java_service/src/main/java/com/boyang/search/controller/SearchController.java`
- 方法：`search(Map<String, Object> requestBody, HttpServletRequest request)`

关键事实：

- AppCode 来自 `X-Search-AppCode` 或用户上下文。
- `jwt.dev-mode=false` 时，`isValidAppCode(...)` 会调用 `SysTenantPolicyService.getByAppCode(...)`。
- 未命中缓存时，Controller 通过 `SearchServiceV2.hybridSearchV2(...)` 获取最终结果。
- Controller 会将最终结果交给 `buildPagedResponse(...)`。
- `buildPagedResponse(...)` 只做分页切片，不裁剪结果字段。
- HTTP JSON 响应结构为：
  - `data.list`
  - `data.total`
  - `data.pageNum`
  - `data.pageSize`
  - `data.hasMore`

结论：只要 `RerankStep` 已经把 QA 权限字段提升到结果顶层，Controller 直接调用和 HTTP JSON 响应都应保持原样返回。

## 3. 新增/更新测试

更新文件：

- `java_service/src/test/java/com/boyang/search/controller/SearchControllerQaPermissionProjectionTest.java`

### 3.1 直接业务入口测试

测试用例：

- `searchResponseKeepsQaPermissionProjectionFields`

覆盖流程：

1. 构造 `SearchController`。
2. Mock `SearchServiceV2` 返回一条 QA 命中结果。
3. Mock `SearchCacheService`，确保缓存未命中。
4. Mock `SysTenantPolicyService`，确保 AppCode 走真实校验分支。
5. 直接调用 `controller.search(...)`。
6. 断言 `response.data.list[0]` 保留：
   - `source_index=kb_document_official`
   - `index_code=official`
   - `owner_unit_code=A01`
   - `visible_unit_codes=[A, A01]`

### 3.2 HTTP JSON 测试

测试用例：

- `searchHttpJsonResponseKeepsQaPermissionProjectionFields`

覆盖流程：

1. 使用 `MockMvcBuilders.standaloneSetup(...)` 构造 HTTP 测试入口。
2. 发送 `POST /api/v1/search`。
3. 请求 Header 设置 `X-Search-AppCode=qa-app`。
4. 请求体使用 JSON：
   - `queryText=权限字段`
   - `pageSize=10`
   - `pageNum=1`
   - `searchMode=hybrid`
5. 使用 JSONPath 断言响应字段：
   - `$.code=200`
   - `$.data.list[0].is_qa_answer=true`
   - `$.data.list[0].source_index=kb_document_official`
   - `$.data.list[0].index_code=official`
   - `$.data.list[0].owner_unit_code=A01`
   - `$.data.list[0].visible_unit_codes[0]=A`
   - `$.data.list[0].visible_unit_codes[1]=A01`

## 4. 执行命令与结果

### 4.1 Controller 定向测试

```bash
mvn -q -Dtest=SearchControllerQaPermissionProjectionTest test
```

结果：通过。

### 4.2 QA 权限投影组合回归

```bash
mvn -q "-Dtest=SearchControllerQaPermissionProjectionTest,RerankStepPermissionProjectionTest" test
```

结果：通过。

### 4.3 Java 编译验证

```bash
mvn -q -DskipTests compile
```

结果：通过。

## 5. 已覆盖边界

- Controller 缓存未命中路径。
- `jwt.dev-mode=false` 下的 AppCode 策略校验路径。
- `SearchServiceV2` 返回 QA 命中结果后的分页响应路径。
- `POST /api/v1/search` 的 HTTP JSON 入参和响应序列化路径。
- `visible_unit_codes` 列表类型在 Java 对象响应和 JSON 响应中均保留。
- QA 标识 `is_qa_answer=true` 与权限字段共同保留。

## 6. 未覆盖内容

本轮未连接真实 ES、Redis、PostgreSQL，也未启动完整 Java Web 服务。

原因：本轮目标是验证 Controller HTTP 响应封装是否裁剪字段；真实 ES/Python 过滤、Java Rerank 字段提升已在前序报告和组合回归中覆盖。

后续如需做完整端到端验证，建议启动 Java 服务并调用真实 `/api/v1/search`，同时断言：

- Python QA 返回权限字段。
- Java Rerank 出站保留权限字段。
- Controller HTTP JSON 响应保留权限字段。

