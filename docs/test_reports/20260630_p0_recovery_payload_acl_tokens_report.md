# P0 恢复入库 Payload ACL Token 修复测试报告

## 结论

已修复 `DocTaskRecoveryJob` 恢复重推任务缺失 `acl_tokens_json` 的问题。恢复任务不再让 Python Worker 因字段缺失降级为 `_INTERNAL`，避免 DEPT/PRIVATE/GRANT 文档在 ES 前置过滤阶段被错误放宽。

## 代码变更

- `java_service/src/main/java/com/boyang/search/job/DocTaskRecoveryJob.java`
  - `buildRecoveryPayload` 新增 `acl_tokens_json`。
  - 新增 `buildRecoveryAclTokensJson`。
  - PUBLIC 恢复为 `["_PUBLIC"]`。
  - INTERNAL 恢复为 `["_INTERNAL"]`。
  - DEPT 根据 `deptCode` 生成 `dept::{本级及上级}`。
  - PRIVATE/GRANT 因 `sys_doc_import_task` 未保存 uploader/grantedUsers/grantedRoles，恢复时写入 `["_NO_ACCESS"]`，避免权限扩大。
- `java_service/src/test/java/com/boyang/search/job/DocTaskRecoveryJobPayloadTest.java`
  - 增加 PUBLIC/INTERNAL/DEPT/PRIVATE/GRANT 的 ACL token 断言。

## 第一性原理说明

恢复任务的本质是“用数据库里持久化下来的事实重建 Redis payload”。当前 `sys_doc_import_task` 只有 `visibility` 和 `deptCode`，没有上传人和授权主体。因此：

- 能安全重建 PUBLIC、INTERNAL、DEPT。
- 不能安全重建 PRIVATE、GRANT。
- 对不能安全重建的权限，正确策略是保守拒绝 ES 前置命中，而不是扩大成 `_INTERNAL`。

后续如需恢复 PRIVATE/GRANT 的完整可见性，需要扩展任务表或恢复任务查询 registry/ACL 权威表。

## 已执行测试

```powershell
mvn -Dtest=DocTaskRecoveryJobPayloadTest test
```

执行目录：

```text
E:\project\AI\knowledge-base\java_service
```

结果：

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 覆盖用例

- 历史 URL 编码文件名恢复为中文文件名。
- DEPT 文档恢复 `ownerUnitCode/visibleUnitCodes/permissionVersion`。
- DEPT 文档恢复 `acl_tokens_json=["dept::620102","dept::6201","dept::62"]`。
- 空部门 INTERNAL 文档恢复为 `ownerUnitCode=global` 且 `acl_tokens_json=["_INTERNAL"]`。
- PUBLIC 文档恢复 `acl_tokens_json=["_PUBLIC"]`。
- PRIVATE 文档不会扩大为 INTERNAL，恢复为 `acl_tokens_json=["_NO_ACCESS"]`。
- GRANT 文档不会扩大为 INTERNAL，恢复为 `acl_tokens_json=["_NO_ACCESS"]`。

## 剩余风险

- `sys_doc_import_task` 未保存 `targetIndex`，恢复任务仍可能使用默认索引重推，后续需单独修复。
- `sys_doc_import_task` 未保存 uploader/grantedUsers/grantedRoles，PRIVATE/GRANT 恢复只能保守拒绝，后续需扩展持久化字段或恢复时查询权威 ACL 表。
