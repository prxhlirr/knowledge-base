# P1 ACL 投影覆盖 QA 与单位字段测试报告

## 结论

已扩展 `DocAclProjectionService` 的 ES 权限投影能力：

- 角色/用户 ACL token 授予、撤销时，除 chunk、`kb_doc_meta`、`kb_doc_search` 外，新增同步到 QA 写别名 `kb_qa_write`。
- 新增单位权限投影同步能力，可将 `owner_unit_code`、`visible_unit_codes`、`permission_version` 同步到 chunk、`kb_doc_meta`、`kb_doc_search`、QA 四类索引。

本步只完成投影服务能力与测试。`KbDocRegistryService.updateMeta` 的 `visibility/deptCode` 变更入口尚未接入该能力，下一步单独实施。

## 代码变更

- `java_service/src/main/java/com/boyang/search/service/DocAclProjectionService.java`
  - 新增 `qaWriteIndex`，默认 `kb_qa_write`。
  - `grantToken/revokeToken` 现同步 4 类索引：
    - chunk 主索引
    - `kb_doc_meta_write`
    - `kb_doc_search_write`
    - `kb_qa_write`
  - 新增 `syncUnitProjection`：
    - chunk 索引同时更新顶层与 `metadata.*` 单位字段。
    - doc_meta/doc_search/QA 更新顶层单位字段。
- `java_service/src/test/java/com/boyang/search/service/DocAclProjectionServiceTest.java`
  - 捕获 `UpdateByQueryRequest`，验证 token 投影包含 QA。
  - 验证单位投影覆盖 chunk/doc_meta/doc_search/QA。
  - 验证 chunk 脚本包含 `metadata.visible_unit_codes`。
  - 验证 QA 脚本包含 `visible_unit_codes` 与 `permission_version`。

## 重要设计取舍

当前 `kb_acl_projection_task` 只能保存 `aclToken`，无法保存完整的 `ownerUnitCode/visibleUnitCodes/permissionVersion`。因此单位投影失败时本步只记录日志，不复用 ACL token 重试任务，避免把单位同步错误重试成 token 同步。

后续若要保证单位投影也具备可靠补偿，需要扩展任务表结构或新增专用单位投影任务表。

## 已执行测试

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m'; mvn -Dtest=DocAclProjectionServiceTest test
```

结果：

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

回归测试：

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m'; mvn -Dtest=EsRecallUtilsTest test
```

结果：

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m'; mvn -Dtest=DocTaskRecoveryJobPayloadTest test
```

结果：

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

```powershell
python -m py_compile ai_service\task_worker.py
```

结果：通过。

## 覆盖范围

- ACL grant 同步到 `kb_qa_write`。
- ACL revoke 使用同一投影路径，覆盖范围与 grant 一致。
- 单位字段可同步到 4 类索引。
- 不影响前序 P0 的单位过滤语义测试。
- 不影响恢复 payload ACL token 测试。

## 剩余工作

- 将 `KbDocRegistryService.updateMeta` 中的 `deptCode/visibility` 变更接入 `syncUnitProjection`。
- 如需强一致补偿，扩展 `kb_acl_projection_task` 或新增单位投影任务表。
