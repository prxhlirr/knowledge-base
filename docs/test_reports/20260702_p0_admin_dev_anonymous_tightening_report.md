# P0-4 管理端写操作禁用 dev-anonymous 兜底测试报告

## 1. 实施背景

P0-3 已将 `/api/v1/admin/**` 从“仅内部 token 放行”调整为“双凭证语义”：内部 token 有效，并且必须解析用户身份后写入 `UserContextHolder`。

继续从第一性原理复核后发现：`resolveIdentity` 中保留了历史开发模式兜底逻辑。当 `jwt.dev-mode=true` 且请求没有 `X-User-Id` 时，会自动构造 `dev-anonymous`。这个逻辑对离线检索调试是便利的，但对管理端写操作不合适。

管理端入库会产生数据库任务、Redis 队列、ES 索引和权限投影。它必须有明确操作者，不能用匿名开发身份代替。

## 2. 本次优化内容

### 2.1 修改文件

- `java_service/src/main/java/com/boyang/search/security/JwtAuthInterceptor.java`
- `java_service/src/test/java/com/boyang/search/security/JwtAuthInterceptorAdminIdentityTest.java`

### 2.2 代码变更

1. 将 `resolveIdentity(request, response)` 调整为：

```java
resolveIdentity(request, response, allowDevAnonymous)
```

2. 管理端路由调用：

```java
resolveIdentity(request, response, false)
```

表示管理端写操作不允许 `dev-anonymous` 兜底。

3. 非管理端普通请求调用：

```java
resolveIdentity(request, response, true)
```

表示保留离线开发检索场景下的匿名兜底能力。

## 3. 测试用例

新增用例：

```text
adminRouteRejectsDevAnonymousFallback
```

测试条件：

- 请求路径：`/api/v1/admin/doc/upload`
- 内部 token 有效
- `jwt.dev-mode=true`
- `JwtVerifier.fromHeaders` 返回空用户身份

预期结果：

- `preHandle` 返回 `false`
- HTTP 状态码为 `401`
- `UserContextHolder` 不写入身份
- 响应包含“管理端操作必须携带有效用户身份”

同时回归：

1. 管理端携带明确身份时可放行。
2. 管理端缺少身份时拒绝。
3. `/api/v1/internal/**` 保持服务凭证语义。
4. `DocIngestService` 底层身份边界和可信系统来源白名单仍然成立。

## 4. 测试命令与结果

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
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-07-02T14:39:59+08:00
```

## 5. 生产影响分析

1. 生产环境不受 `dev-anonymous` 影响。
   - 生产应走 JWT 验签。
   - 本次修复主要防止开发/测试环境的写操作被匿名兜底掩盖问题。

2. 管理端写操作审计更准确。
   - 入库批次、任务、权限投影链路不再可能以 `dev-anonymous` 作为操作者。

3. 离线检索调试能力保留。
   - 非管理端普通请求仍可在开发模式下使用 `dev-anonymous`。

## 6. 结论

本次 P0-4 已完成并通过针对性测试。

优化后，开发匿名身份只服务于读/检索调试，不再进入管理端写操作链路。管理端入库必须具备明确操作者，和 Service 层的失败关闭策略保持一致。
