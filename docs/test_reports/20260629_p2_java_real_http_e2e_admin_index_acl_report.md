# P2 Java 真实 HTTP E2E 与管理员索引 ACL 修复报告

## 1. 本轮任务

继续执行 Java `/api/v1/search` 真实服务级验证。

目标：

- 启动真实 Java Spring Boot 服务。
- 调用真实 `/api/v1/search` HTTP 接口。
- 验证管理员请求是否能覆盖全部文档索引。
- 验证 QA/文档权限字段是否能随结果返回。

## 2. 启动环境

启动端口：

- `18080`

启动覆盖参数：

- `--server.port=18080`
- `--elasticsearch.host=127.0.0.1`
- `--elasticsearch.port=9200`
- `--elasticsearch.username=`
- `--elasticsearch.password=`
- `--ai.service.host=http://127.0.0.1:8001`
- `--qa.answer.ai-service-url=http://127.0.0.1:8001`
- `--spring.redis.host=127.0.0.1`
- `--spring.redis.port=6379`
- `--xxl.job.enabled=false`
- `--jwt.dev-mode=true`

依赖探测结果：

- PostgreSQL `127.0.0.1:5432`：可达。
- Elasticsearch `127.0.0.1:9200`：可达。
- Python AI `127.0.0.1:8001`：可达。
- Redis `127.0.0.1:6379`：不可达，但 `PolicyVersionService` 已验证为 fail-safe，Redis 异常时降级为版本 `0`，不阻断搜索主链路。

## 3. 真实数据库状态

`sys_tenant_policy` 中可用 AppCode：

- `ADMIN_MASTER_KEY`
- `VEND_A_7788`
- `VEND_B_9900`
- `boyang-kb`

`kb_index_acl_subjects` 中当前索引 ACL：

- `kb_document_official` 仅允许角色 `official_reader`。
- `kb_document_public` 仅允许角色 `public_reader`。

## 4. 首轮 E2E 发现的问题

使用 `ADMIN_MASTER_KEY` 调用：

```http
POST /api/v1/search
X-Search-AppCode: ADMIN_MASTER_KEY
```

查询词：

- `总书记什么时候强调的`
- `网络生态治理需要多少钱`
- `网络强国建设 挑战 机遇`

首轮结果：

- HTTP 返回 `code=200`。
- 结果总数为 `0`。
- 日志中 `admin_bypass=true`。
- 但 `resolved_index` 只包含：
  - `kb_document_law`
  - `kb_document_notice`
  - `kb_document_v1`
  - `kb_document_news`
- 未包含：
  - `kb_document_public`
  - `kb_document_official`

根因：

- `IndexAclGuard.isReadable(...)` 没有对 `identity.isSuperAdmin()` 做短路放行。
- `kb_document_official/public` 存在角色级 ALLOW 规则。
- dev 超管身份没有 `official_reader/public_reader` 角色。
- 因此超管虽然在后置 `PermissionGuard` 中被标记为 `admin_bypass=true`，但在前置索引 ACL 阶段已经被过滤掉部分索引。

该问题直接违反需求：

- 管理员可查看所有的文档。

## 5. 修复内容

修改文件：

- `java_service/src/main/java/com/boyang/search/service/IndexAclGuard.java`

修复逻辑：

```java
if (identity != null && identity.isSuperAdmin()) {
    return true;
}
```

修复含义：

- 只在索引 ACL 层对超管放行。
- 不改变租户 `allowedIndices` 的最大范围约束。
- 不改变普通用户、角色、部门的 ACL 规则。
- 与后置 `PermissionGuard` 的管理员放行语义保持一致。

## 6. 新增测试

新增文件：

- `java_service/src/test/java/com/boyang/search/service/IndexAclGuardTest.java`

测试用例：

- `superAdminCanReadPhysicalIndexEvenWhenIndexHasRoleRules`

验证点：

- 当索引是合法物理文档索引。
- 当前身份是超管。
- 即使索引存在角色 ACL 规则。
- `filterReadableScope("kb_document_official", superAdmin)` 仍返回 `kb_document_official`。

## 7. 自动化验证结果

### 7.1 新增单测

```bash
mvn -q -Dtest=IndexAclGuardTest test
```

结果：通过。

### 7.2 权限投影组合回归

```bash
mvn -q "-Dtest=IndexAclGuardTest,SearchControllerQaPermissionProjectionTest,RerankStepPermissionProjectionTest" test
```

结果：通过。

### 7.3 Java 编译

```bash
mvn -q -DskipTests compile
```

结果：通过。

## 8. 修复后真实 HTTP E2E 复测

修复后重新启动真实 Java 服务并调用 `/api/v1/search`。

复测日志中的 resolved index：

```text
kb_document_public,kb_document_official,kb_document_law,kb_document_notice,kb_document_v1,kb_document_news
```

结论：

- 管理员索引范围修复成功。
- `kb_document_public` 与 `kb_document_official` 已重新进入管理员可读范围。

复测查询结果：

- `总书记什么时候强调的`：返回 `total=4`。
- `网络生态治理需要多少钱`：返回 `total=4`。
- `网络强国建设 挑战 机遇`：返回 `total=4`。

## 9. 仍未完全闭环的点

本轮真实 HTTP E2E 没有拿到 QA 命中：

- `qa_count=0`

但已确认 `kb_qa_pairs` 中 QA 数据本身具备：

- `source_index=kb_document_official`
- `index_code=official`

同时确认普通 chunk 历史数据缺少权限投影字段：

```json
{
  "index": "kb_document_official",
  "total": 669,
  "with_source_index": 0
}
```

这说明：

- Java 响应层已具备字段透传能力。
- QA 索引已完成权限字段补齐。
- 但普通文档 chunk 索引仍需要历史数据补齐 `source_index/index_code/owner_unit_code/visible_unit_codes`。

## 10. 下一步建议

下一步应执行普通文档 chunk 索引历史字段补齐任务：

- 扫描 `kb_document_*` 物理索引。
- 根据物理索引名补齐 `source_index`。
- 根据索引路由规则或索引名补齐 `index_code`。
- 根据数据库单位字段补齐 `owner_unit_code` 与 `visible_unit_codes`。
- 补齐后重新跑真实 `/api/v1/search` E2E，断言普通文档结果和 QA 结果都能返回权限字段。

