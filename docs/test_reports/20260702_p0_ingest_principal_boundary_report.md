# P0-1 用户入库与系统入库身份边界加固测试报告

## 1. 实施背景

从第一性原理看，文档入库权限链路必须先确认“谁在发起入库”，再决定是否允许写入任务队列和后续 ES 权限投影。

本次排查确认：`DocIngestService.ingest(DocIngestRequest req, String operatorId)` 是更底层的入库主入口，历史逻辑在 `operatorId == null` 时会跳过 `validatePermission`，继续创建批次并异步派发任务。若用户入口因拦截器、JWT、线程上下文问题丢失操作者身份，就可能被错误降级为系统入库。

## 2. 本次优化内容

### 2.1 加固文件

- `java_service/src/main/java/com/boyang/search/service/DocIngestService.java`
- `java_service/src/test/java/com/boyang/search/service/DocIngestServiceUnitPermissionProjectionTest.java`

### 2.2 代码变更

1. 新增 `validateIngestPrincipal(DocIngestRequest req, String operatorId)`。
   - 有非空操作者身份：继续后续用户权限校验。
   - 无操作者身份：仅允许可信系统同步来源继续执行。
   - 无操作者身份且来源不可信：直接抛出 `SecurityException`。

2. 新增 `isTrustedSystemIngestSource(String sourceSystem)`。
   - 允许来源：`DB_HTML_SYNC`、`DB_DOC_SYNC`、`DOC_SYNC`、`OA`、`DMS`、`ARCHIVE`、`SYSTEM_*`、`XXL_JOB_*`、`*_SYNC`。
   - 目的：保留数据库同步、归档同步、任务调度类入口的无登录态入库能力。

3. 在 `ingest(req, operatorId)` 中前置调用身份边界校验。
   - 发生在 `validateRequest(req)` 之后、`initBatch` 和异步派发之前。
   - 目的：失败关闭，不创建批次，不写任务队列。

4. 将用户权限校验条件从 `operatorId != null` 收紧为 `operatorId != null && !operatorId.trim().isEmpty()`。
   - 目的：避免空白操作者字符串触发错误的用户权限校验分支。

## 3. 测试用例

新增并通过以下用例：

1. `userIngestWithoutOperatorAndUntrustedSourceIsRejected`
   - 输入：`sourceSystem=LOCAL_DIR_ADMIN`，`operatorId=null`
   - 期望：抛出 `SecurityException`
   - 验证点：用户类入口缺少操作者身份时不能降级为系统入库。

2. `trustedSystemSyncSourceCanRunWithoutOperator`
   - 输入：`sourceSystem=DB_DOC_SYNC`，`operatorId=null`
   - 期望：不抛异常
   - 验证点：可信系统同步入口仍可无登录态运行。

3. `blankOperatorIsRejectedForUntrustedUserIngestSource`
   - 输入：`sourceSystem=UPLOAD_ADMIN`，`operatorId=" "`
   - 期望：抛出 `SecurityException`
   - 验证点：空白操作者不能绕过身份边界。

同时保留原有单位权限投影用例：

1. `unitPermissionProjectionUsesDeptChainFromJavaPermissionDomain`
2. `unitPermissionProjectionFallsBackToGlobalForBlankDept`

## 4. 测试命令与结果

执行目录：

```powershell
E:\project\AI\knowledge-base\java_service
```

执行命令：

```powershell
mvn "-Dtest=DocIngestServiceUnitPermissionProjectionTest" test
```

执行结果：

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-07-02T10:04:51+08:00
```

## 5. 边缘场景与后续测试建议

1. HTTP 用户上传入口
   - 建议补充集成测试：登录用户上传时必须能正确从 `UserContextHolder` 读取 `userId`。

2. 定时同步入口
   - 建议补充集成测试：`DB_DOC_SYNC`、`DB_HTML_SYNC` 在无 JWT 场景下仍能创建批次。

3. 来源标识治理
   - 当前可信来源白名单落在代码中，适合 P0 快速收口。
   - 后续如需运营动态配置，建议迁移为数据库或配置中心枚举，并增加启动时校验。

## 6. 结论

本次 P0-1 已完成并通过针对性单元测试。

优化后，用户入库入口不再允许在缺少操作者身份时继续执行；系统同步入口仍保留无登录态入库能力。该变更不改动 ES mapping、不改动 Python Worker payload 结构、不影响既有权限投影字段生成逻辑。
