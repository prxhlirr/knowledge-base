# P0 索引 ACL 与相似推荐权限修复测试报告

测试时间：2026-06-25

## 1. 本批次任务

| 编号 | 任务 | 状态 |
| --- | --- | --- |
| P0-1 | 手动 PostgreSQL 建表脚本落盘 | 完成 |
| P0-2 | 相似推荐不得绕过角色可读索引范围 | 完成 |
| P0-3 | 后置权限过滤不再使用 `organization` 作为权限键 | 完成 |

## 2. 变更范围

### 2.1 数据库脚本

新增：

- `java_service/src/main/resources/db/manual/20260625_p0_index_acl_pgsql.sql`

内容：

1. 创建 `public.kb_index_acl_subjects`。
2. 创建 `idx_kias_index_scope`、`idx_kias_subject`、`idx_kias_effect_expire`。
3. 提供 PostgreSQL 校验 SQL。
4. 提供角色授权样例 SQL。

说明：

该脚本为手动执行脚本，不是 Flyway migration，符合“P0 数据库修复由 DBA/运维手动执行”的约束。

### 2.2 Java 代码

修改：

- `java_service/src/main/java/com/boyang/search/service/SimilarityService.java`
- `java_service/src/main/java/com/boyang/search/service/SearchServiceV2.java`
- `java_service/src/main/java/com/boyang/search/pipeline/steps/RerankStep.java`

核心变化：

1. `SimilarityService` 注入 `SysTenantPolicyService` 与 `SearchIndexResolver`。
2. 编辑器相似推荐先解析当前 appCode/身份可读的 chunk 索引范围。
3. 普通用户相似推荐不再优先查缺少 `source_index` 的 `kb_doc_meta_read`，改走权限范围内的 chunk fallback。
4. evidence 补抓也使用同一个可读索引范围。
5. 相似推荐缓存 key 纳入可读索引范围，避免权限变化后复用旧缓存。
6. `RerankStep` 输出 `permission_guard_key`，取自 `metadata.source`。
7. `SearchServiceV2` 后置过滤按 `permission_guard_key/source_name/source/file_name` 解析权限键，不再把 `organization` 当权限键。

## 3. 测试命令与结果

### 3.1 编译测试

命令：

```powershell
$env:MAVEN_OPTS='-Xmx768m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=128m'
mvn -q -DskipTests compile
```

结果：通过。

结论：

本批次 Java 代码无编译错误。

### 3.2 目标单元测试

命令：

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=96m'
mvn -q '-Dtest=IndexAliasResolverTest,IndexAclSubjectServiceTest' '-DforkCount=0' test
```

结果：通过。

覆盖内容：

1. 索引别名解析。
2. 索引 ACL 服务相关单测。

### 3.3 全量测试

命令：

```powershell
$env:MAVEN_OPTS='-Xmx768m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=128m'
mvn -q test
```

结果：未通过。

失败原因：

Surefire fork JVM 因本机 native memory 分配失败退出，不是业务断言失败。`target/surefire-reports/2026-06-25T16-17-56_363-jvmRun1.dumpstream` 显示：

```text
There is insufficient memory for the Java Runtime Environment to continue.
Native memory allocation (malloc) failed
```

结论：

全量测试当前受本机 JVM native memory 限制阻塞。已通过编译和目标单测验证本批次核心改动，后续可在 CI 或内存更充足环境执行完整测试。

## 4. 功能验证推导

### 4.1 角色索引权限

预期链路：

1. DBA 手动执行 `kb_index_acl_subjects` 建表。
2. 写入 `ROLE -> kb_document_*` 授权规则。
3. `SearchIndexResolver.resolve()` 根据租户策略和 `IndexAclGuard` 返回可读物理索引。
4. 主检索和相似推荐使用可读索引范围。

本批次已完成：

1. 手动 PostgreSQL 脚本。
2. 相似推荐接入可读索引解析。

未在本地执行：

1. 实际数据库建表。
2. 实际插入角色授权数据。

原因：

用户要求 P0 数据库修复手动执行；当前批次只提供脚本与代码闭环。

### 4.2 相似推荐权限

修复前：

1. `SimilarityService` chunk fallback 固定查询 `editor.similarity.chunk-fallback-index`，默认 `kb_document`。
2. evidence 补抓也固定查询该配置。
3. 普通用户可在相似推荐路径扫全 `kb_document` 读别名。

修复后：

1. 先通过 `SearchIndexResolver` 解析可读索引范围。
2. 无可读索引返回空列表并标记 `skipReason=no_readable_index`。
3. 普通用户走权限范围内 chunk fallback。
4. evidence 补抓使用同一可读索引范围。
5. 超管仍可使用 `kb_doc_meta_read` 主路径。

风险说明：

当前 `kb_doc_meta_v2` 没有 `source_index` 字段，所以普通用户暂时跳过 meta KNN，这是 P0 安全优先策略。P1 补齐 `kb_doc_meta_v3.source_index` 后，可以恢复普通用户 meta KNN 并加 `source_index` filter。

### 4.3 后置权限过滤

修复前：

`SearchServiceV2` 注释说按 `doc_id`，实际代码取 `file_name/organization`。当 `organization` 是机构名时，存在权限键语义错误。

修复后：

1. `RerankStep` 显式输出 `permission_guard_key`。
2. `SearchServiceV2` 按以下顺序取权限键：
   - `permission_guard_key`
   - `source_name`
   - `source`
   - `file_name`
3. 不再使用 `organization` 作为权限校验键。

风险说明：

当前 `PermissionGuard` 的权威键仍是 `kb_doc_registry.source_name`。如果系统未来允许不同单位上传同名文件并在 registry 中不能唯一定位，需要进一步引入稳定 `doc_id` 权限主键。本批次先修复“机构名误用”问题。

## 5. 回归风险

| 风险 | 等级 | 控制 |
| --- | --- | --- |
| 普通用户相似推荐从 doc_meta KNN 降级到 chunk fallback，性能或排序可能变化 | 中 | P1 补 `kb_doc_meta_v3.source_index` 后恢复 meta KNN |
| `permission_guard_key` 仍基于 `metadata.source`，同名文件唯一性依赖 registry 现状 | 中 | 后续引入稳定 `doc_id` 权限主键 |
| 全量测试未在本机跑通 | 中 | 已完成编译和目标单测；需在 CI/更大内存环境跑全量 |

## 6. 验收结论

本批次 P0 代码和脚本达到可进入联调条件：

1. Java 编译通过。
2. 索引解析/索引 ACL 目标单测通过。
3. PostgreSQL 手动脚本已提供。
4. 相似推荐不再默认扫全 `kb_document`。
5. 后置权限过滤不再使用 `organization` 作为权限键。

下一批建议进入：

1. 在测试库手动执行 PostgreSQL 脚本。
2. 配置两组角色索引授权样例。
3. 使用真实 JWT/角色调用搜索与相似推荐接口做端到端验证。
4. 开始 P1：补 `source_index/index_code` 到 `kb_doc_search/kb_doc_meta/kb_qa`。

