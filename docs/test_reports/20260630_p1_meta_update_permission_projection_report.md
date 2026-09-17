# P1 元数据权限字段更新投影闭环测试报告

## 结论

已修复 `KbDocRegistryService.updateMeta` 只更新 MySQL、不同步 ES 权限投影的问题。

现在当文档管理接口更新 `visibility` 或 `deptCode` 时，会执行完整权限闭环：

1. 更新 `kb_doc_registry`。
2. 重建 `INGEST_INIT` 初始 ACL subject。
3. 保留运行期 active 授权，并基于当前事实表生成完整 `acl_tokens`。
4. 覆盖同步 chunk、`kb_doc_meta`、`kb_doc_search`、QA 四类 ES 索引中的 `acl_tokens`。
5. 同步 `owner_unit_code`、`visible_unit_codes`、`permission_version` 单位权限字段。

## 第一性原理校验

`visibility/deptCode` 不是普通展示字段，而是决定“谁能看到文档”的事实字段。

如果只改数据库：

- 后置鉴权会读取新的 MySQL 权限。
- ES 前置召回仍使用旧 `acl_tokens` / `visible_unit_codes`。
- 结果会出现漏召回或误召回。

因此本次没有采用单 token 增删，而是采用“权限事实表重建 + ES 完整覆盖投影”。这能避免 `PUBLIC -> DEPT`、`DEPT -> PRIVATE` 等场景残留旧 token。

## 代码变更

- `java_service/src/main/java/com/boyang/search/service/DocAclProjectionService.java`
  - 新增 `syncAclTokensProjection`。
  - 支持把完整 `acl_tokens` 覆盖同步到 chunk、`kb_doc_meta_write`、`kb_doc_search_write`、`kb_qa_write`。
  - chunk 同时更新顶层 `acl_tokens` 与 `metadata.acl_tokens`。
- `java_service/src/main/java/com/boyang/search/service/KbDocRegistryService.java`
  - `updateMeta` 增加事务边界。
  - 触碰 `visibility/deptCode` 时重建初始 ACL subject。
  - 从当前 active ACL subject 生成完整 token 集合。
  - 调用 `syncAclTokensProjection` 和 `syncUnitProjection` 同步 ES。
- `java_service/src/test/java/com/boyang/search/service/DocAclProjectionServiceTest.java`
  - 增加 ACL token 完整覆盖投影测试。
- `java_service/src/test/java/com/boyang/search/service/KbDocRegistryServicePermissionMetaUpdateTest.java`
  - 增加普通元数据不触发权限投影测试。
  - 增加权限字段更新触发 ACL 重建、token 覆盖、单位字段同步测试。

## 已执行测试

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m'; mvn -Dtest=DocAclProjectionServiceTest test
```

结果：

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m'; mvn -Dtest=KbDocRegistryServicePermissionMetaUpdateTest test
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

## 测试过程异常说明

首次并行运行两个 Maven 测试命令时，`KbDocRegistryServicePermissionMetaUpdateTest` 命令出现 Surefire fork 启动失败：

```text
Tests run: 0
The forked VM terminated without properly saying goodbye
```

该命令没有进入测试执行阶段。随后按顺序单独执行通过，判断为并行 Maven 进程抢占 Windows JVM 资源导致的测试环境问题，不是代码断言失败。

## 已知边界

- 当前 `kb_acl_projection_task` 仍不能表达“完整 token 列表”或“单位可见链”，因此覆盖投影失败只记录日志。若生产要求强补偿，需要新增专用投影任务表或扩展现有任务表。
- `updateMeta` 对 GRANT 类型不会自动恢复历史 grantedUsers/grantedRoles 入库参数；它会保留当前 active runtime grant。后续若需要编辑 GRANT 的用户/角色集合，应走授权接口而不是元数据接口。
