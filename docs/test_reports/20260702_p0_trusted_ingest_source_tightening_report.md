# P0-2 可信系统入库来源收敛测试报告

## 1. 实施背景

上一轮已补入库身份边界：用户触发入库必须有操作者身份，系统同步入口允许无操作者身份。

继续从第一性原理复核后发现：`sourceSystem` 是 `DocIngestRequest` 字段，本质上属于请求数据。如果允许 `*_SYNC`、`SYSTEM_*` 这类模式匹配，那么请求方只要构造 `UPLOAD_SYNC` 或 `SYSTEM_FAKE`，就可能在操作者身份缺失时被误判为系统入库。

因此本轮目标是把“可无操作者入库”的来源收敛为项目代码中真实存在、已验证的同步来源。

## 2. 代码事实验证

已通过代码搜索确认：

1. 管理端用户入口使用以下来源：
   - `LOCAL_DIR_ADMIN`
   - `UPLOAD_ADMIN`
   - `SFTP_ADMIN`
   - `URL_ADMIN`

2. 数据库同步定时任务使用以下来源：
   - `DB_HTML_SYNC`
   - `DB_DOC_SYNC`

3. Kafka 示例中存在 `OA`，但消费者代码当前为注释状态，不属于已启用的可信免登录入口。

基于以上事实，本轮只放行：

```text
DB_HTML_SYNC
DB_DOC_SYNC
```

## 3. 本次优化内容

### 3.1 修改文件

- `java_service/src/main/java/com/boyang/search/service/DocIngestService.java`
- `java_service/src/test/java/com/boyang/search/service/DocIngestServiceUnitPermissionProjectionTest.java`

### 3.2 代码变更

1. 收敛 `isTrustedSystemIngestSource(String sourceSystem)`。
   - 删除 `source.endsWith("_SYNC")`。
   - 删除 `source.startsWith("SYSTEM_")`。
   - 删除未验证启用的 `OA`、`DMS`、`ARCHIVE`、`DOC_SYNC`。
   - 仅保留 `DB_HTML_SYNC` 和 `DB_DOC_SYNC`。

2. 更新 `ingest(req, operatorId)` 参数注释。
   - 原注释仍表达“operatorId 为 null 时跳过权限校验”，与当前 fail-closed 行为不一致。
   - 已改为“用户入口不允许为空”。

## 4. 测试用例

本轮新增以下两个回归测试：

1. `forgedSyncSuffixSourceIsRejectedWithoutOperator`
   - 输入：`sourceSystem=UPLOAD_SYNC`，`operatorId=null`
   - 期望：抛出 `SecurityException`
   - 验证点：伪造 `_SYNC` 后缀不能绕过用户身份边界。

2. `forgedSystemPrefixSourceIsRejectedWithoutOperator`
   - 输入：`sourceSystem=SYSTEM_FAKE`，`operatorId=null`
   - 期望：抛出 `SecurityException`
   - 验证点：伪造 `SYSTEM_` 前缀不能绕过用户身份边界。

同时回归上一轮已存在用例：

1. 用户入口缺少操作者身份必须拒绝。
2. `DB_DOC_SYNC` 无操作者身份仍允许。
3. 空白操作者不能绕过身份边界。
4. 单位权限投影链路保持不变。

## 5. 测试命令与结果

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
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-07-02T10:08:10+08:00
```

## 6. 生产影响分析

1. 不影响管理端正常入库。
   - 管理端入口通过 `docIngestService.ingest(req)` 读取 `UserContextHolder` 中的用户身份。
   - 若用户已登录，继续走 `validatePermission`。
   - 若用户上下文丢失，按安全策略拒绝。

2. 不影响当前数据库同步任务。
   - `DatabaseHtmlSyncJobHandler` 中真实使用的 `DB_HTML_SYNC`、`DB_DOC_SYNC` 仍被允许无操作者身份入库。

3. 会阻断未显式纳入白名单的免登录入口。
   - 这是符合预期的安全收敛。
   - 后续若启用 Kafka/OA/DMS/ARCHIVE 免登录同步，应先为其建立内部鉴权或专用系统入口，再加入可信来源集合。

## 7. 结论

本次 P0-2 已完成并通过针对性单元测试。

优化后，`sourceSystem` 不能再通过宽泛模式匹配伪造系统入库来源；当前仅项目内真实启用的数据库同步来源可以无操作者身份入库，权限入口边界更符合生产环境的失败关闭原则。
