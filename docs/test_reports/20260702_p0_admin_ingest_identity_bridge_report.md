# P0-3 管理端入库 HTTP 身份传递加固测试报告

## 1. 实施背景

P0-1/P0-2 已经把 `DocIngestService` 的底层入库边界收紧为：

1. 用户类入库必须有操作者身份。
2. 只有 `DB_HTML_SYNC`、`DB_DOC_SYNC` 这两个已验证系统同步来源可以无操作者身份入库。

继续从 HTTP 链路复核后发现：`DocImportController` 的管理端入库接口均调用 `docIngestService.ingest(req)`，操作者身份依赖 `UserContextHolder`。但 `JwtAuthInterceptor` 对 `/api/v1/admin/**` 只校验 `X-Internal-Token` 后直接放行，没有解析 JWT 或开发模式 Header，也没有写入 `UserContextHolder`。

这会导致管理端入库请求即使携带内部 token，也无法把真实操作者传入 Service 层。

## 2. 代码事实验证

已确认管理端入库入口：

- `/api/v1/admin/doc/batch_import`
- `/api/v1/admin/doc/upload`
- `/api/v1/admin/doc/sftp_import`
- `/api/v1/admin/doc/url_import`

均调用：

```java
docIngestService.ingest(req)
```

而 `DocIngestService.ingest(req)` 现在会从 `UserContextHolder` 读取当前操作者。

## 3. 本次优化内容

### 3.1 修改文件

- `java_service/src/main/java/com/boyang/search/security/JwtAuthInterceptor.java`
- `java_service/src/test/java/com/boyang/search/security/JwtAuthInterceptorAdminIdentityTest.java`

### 3.2 代码变更

1. `/api/v1/internal/**` 保持原行为。
   - 只校验 `X-Internal-Token`。
   - 不绑定用户身份。
   - 适用于服务间回调、Worker 回调等非用户操作。

2. `/api/v1/admin/**` 改为双凭证语义。
   - 先校验 `X-Internal-Token`。
   - 再解析用户身份。
   - 用户身份缺失时返回 `401`。
   - 解析成功后写入 `UserContextHolder`，供入库 Service 获取操作者。

3. 抽取 `resolveIdentity(request, response)`。
   - 生产网关模式：必须携带并验签 JWT。
   - 开发模式：允许从 Header 构造临时身份。
   - 避免 admin/search 两条路由重复实现身份解析。

4. 抽取 `bindIdentity(identity)`。
   - 先构建 ACL Token。
   - 再写入 `UserContextHolder`。
   - 保证入库、检索、后置权限校验读取到一致身份上下文。

## 4. 测试用例

新增测试类：

```text
JwtAuthInterceptorAdminIdentityTest
```

覆盖用例：

1. `adminRouteRequiresInternalTokenAndBindsOperatorIdentity`
   - 输入：`/api/v1/admin/doc/batch_import`
   - 条件：内部 token 有效，开发模式 Header 提供用户身份
   - 期望：请求放行，`UserContextHolder` 写入 `admin-user`

2. `adminRouteRejectsWhenOperatorIdentityIsMissing`
   - 输入：`/api/v1/admin/doc/batch_import`
   - 条件：内部 token 有效，但无法解析用户身份
   - 期望：返回 `401`，不写入 `UserContextHolder`

3. `internalRouteKeepsServiceCredentialOnlySemantics`
   - 输入：`/api/v1/internal/task/callback`
   - 条件：内部 token 有效
   - 期望：请求放行，但不写入用户身份

同时回归：

```text
DocIngestServiceUnitPermissionProjectionTest
```

确保 Service 层身份边界和可信系统来源收敛仍然成立。

## 5. 测试命令与结果

执行目录：

```powershell
E:\project\AI\knowledge-base\java_service
```

执行命令：

```powershell
mvn "-Dtest=JwtAuthInterceptorAdminIdentityTest,DocIngestServiceUnitPermissionProjectionTest" test
```

执行结果：

```text
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-07-02T10:15:28+08:00
```

## 6. 生产影响分析

1. 管理端入库链路更严格。
   - 仅有内部 token 不再足以执行 `/api/v1/admin/**` 用户操作。
   - 必须同时具备可解析用户身份。

2. 服务间内部回调不受影响。
   - `/api/v1/internal/**` 仍按内部 token 放行。
   - 不要求 JWT，不绑定操作者。

3. `DocIngestService` 的失败关闭策略不需要放松。
   - HTTP 管理端会正确传递操作者。
   - 若身份链路异常，入库会在 HTTP 拦截器或 Service 层被拒绝。

## 7. 结论

本次 P0-3 已完成并通过针对性测试。

优化后，管理端 HTTP 入库入口、Service 入库身份边界、系统同步免登录白名单三者语义对齐：用户操作必须有用户身份，服务同步必须来自已验证系统来源，内部回调仍保持服务凭证模式。
