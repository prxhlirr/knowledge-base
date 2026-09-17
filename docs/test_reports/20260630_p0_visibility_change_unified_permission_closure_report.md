# P0 visibility 变更统一权限闭环测试报告

## 结论

已将旧的 `/api/doc/perm/visibility` 可见度变更链路收口到 `KbDocRegistryService.updateMeta`。

旧实现只更新 ES `metadata.visibility`，会绕过：

- `kb_doc_registry` 权威权限字段
- `kb_doc_acl_subjects` 初始 ACL 重建
- `acl_tokens` 完整覆盖投影
- `owner_unit_code / visible_unit_codes / permission_version` 单位投影

新实现统一走 registry 权限闭环，避免 MySQL 后置鉴权与 ES 前置召回使用不同权限事实。

## 代码变更

- `java_service/src/main/java/com/boyang/search/service/DocPermissionService.java`
  - 移除旧 ES `update_by_query metadata.visibility` 直写逻辑。
  - 注入 `KbDocRegistryService`。
  - `addVisibilityChange` 改为：
    1. 查询最新版 registry。
    2. 校验 `visibility=DEPT` 时必须有 `deptCode`。
    3. 调用 `registryService.updateMeta`。
    4. 写权限事件。
    5. 清理搜索缓存。
  - 保留三参重载方法，兼容旧调用。
- `java_service/src/main/java/com/boyang/search/controller/DocPermissionController.java`
  - `/visibility` 支持可选 `deptCode`。
- `java_service/src/test/java/com/boyang/search/service/DocPermissionServiceVisibilityChangeTest.java`
  - 验证 visibility 变更调用 registry 权限闭环。
  - 验证 `DEPT` 缺少部门时拒绝。
  - 验证 `DEPT` 带部门时透传 `deptCode`。

## 已执行测试

首次并行运行 Maven 测试时触发本机 JVM native memory OOM，测试未进入断言阶段。

随后使用低内存、无 fork、顺序执行：

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:CICompilerCount=2 -XX:+UseSerialGC'; mvn -Dtest=DocPermissionServiceVisibilityChangeTest -DforkCount=0 test
```

结果：

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

回归测试：

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:CICompilerCount=2 -XX:+UseSerialGC'; mvn -Dtest=KbDocRegistryServicePermissionMetaUpdateTest -DforkCount=0 test
```

结果：

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 边界说明

- 本轮没有修改 `KbDocRegistryService.updateMeta` 的语义，只把旧入口转接到它。
- 切换到 `GRANT` 的授权集合仍应通过授权接口维护，不建议通过 visibility 接口隐式修改授权主体。
