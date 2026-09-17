# P1 ACL/单位完整投影补偿任务测试报告

## 结论

已扩展 `kb_acl_projection_task` 的补偿能力，使 ES 权限投影失败后不再只能重试单个 `aclToken` 增删。

本轮新增两类可重放任务：

- `ACL_TOKENS_SYNC`：完整覆盖同步 `acl_tokens`。
- `UNIT_SYNC`：完整同步 `owner_unit_code`、`visible_unit_codes`、`permission_version`。

旧任务仍保持兼容：

- `TOKEN_DELTA`：单个 token 的 `GRANT/REVOKE`。

## 第一性原理校验

权限投影补偿任务的本质是“把 MySQL 权威权限事实重新投影到 ES”。

单 token 增删只能表达运行期授权增减，不能表达：

- `PUBLIC -> DEPT`
- `DEPT -> PRIVATE`
- 文档归属单位变化
- 单位可见链变化

这些场景都需要保存完整投影载荷，否则重试时无法知道应写入的 token 集合或单位链。因此本轮新增 `task_type/payload_json`，而不是继续复用 `acl_token` 字段塞特殊值。

## 数据库脚本

新增手工 PostgreSQL 脚本：

- `java_service/src/main/resources/db/manual/20260630_p1_extend_acl_projection_task_payload_pgsql.sql`

脚本特性：

- `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`
- 新增 `task_type VARCHAR(32) NOT NULL DEFAULT 'TOKEN_DELTA'`
- 新增 `payload_json TEXT`
- 新增 `(task_type, status, next_retry_at)` 索引
- 不修改旧字段 `acl_token/operation` 的非空约束，避免影响历史任务

## 代码变更

- `java_service/src/main/java/com/boyang/search/entity/KbAclProjectionTask.java`
  - 新增 `taskType`
  - 新增 `payloadJson`
- `java_service/src/main/java/com/boyang/search/service/DocAclProjectionService.java`
  - `retryTask` 支持按 `task_type` 分派。
  - `syncAclTokensProjection` 在 chunk 主投影失败时创建 `ACL_TOKENS_SYNC` 补偿任务。
  - `syncUnitProjection` 在 chunk 主投影失败时创建 `UNIT_SYNC` 补偿任务。
  - 重试新任务时会重新执行四类索引投影：chunk、`kb_doc_meta`、`kb_doc_search`、QA。
- `java_service/src/test/java/com/boyang/search/service/DocAclProjectionServiceTest.java`
  - 覆盖完整 token 投影失败写入补偿任务。
  - 覆盖 `ACL_TOKENS_SYNC` 重试成功。
  - 覆盖 `UNIT_SYNC` 重试成功。

## 已执行测试

首次执行：

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m'; mvn -Dtest=DocAclProjectionServiceTest test
```

结果：Surefire fork 启动失败，测试未执行。

```text
Tests run: 0
The forked VM terminated without properly saying goodbye
```

随后使用无 fork 模式：

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m'; mvn -Dtest=DocAclProjectionServiceTest -DforkCount=0 test
```

结果：

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

回归测试：

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m'; mvn -DskipTests compile
```

结果：

```text
BUILD SUCCESS
```

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m'; mvn -Dtest=KbDocRegistryServicePermissionMetaUpdateTest -DforkCount=0 test
```

结果：

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m'; mvn -Dtest=EsRecallUtilsTest -DforkCount=0 test
```

结果：

```text
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 测试过程异常说明

并行 Maven 命令触发过本机 JVM 内存不足：

```text
There is insufficient memory for the Java Runtime Environment to continue.
Could not reserve enough space for object heap
```

这属于本机并行测试资源问题。顺序无 fork 执行后，相关测试均通过。

## 上线执行顺序

1. DBA 手工执行：

```sql
java_service/src/main/resources/db/manual/20260630_p1_extend_acl_projection_task_payload_pgsql.sql
```

2. 发布 Java 服务。
3. 观察 `kb_acl_projection_task`：

```sql
SELECT task_type, status, COUNT(*)
FROM public.kb_acl_projection_task
GROUP BY task_type, status
ORDER BY task_type, status;
```

4. 若有 `FAILED/DEAD`，按 `last_error` 定位 ES 或 mapping 问题。

## 本轮边界

- 新补偿任务仍以 chunk 主索引失败为创建条件，辅助索引失败沿用 warn 日志。原因是现有架构把 chunk 作为主投影一致性锚点，避免辅助索引短暂抖动制造大量重复补偿任务。
- 如后续要求辅助索引也强补偿，可进一步把任务粒度扩展为“索引级投影任务”。
