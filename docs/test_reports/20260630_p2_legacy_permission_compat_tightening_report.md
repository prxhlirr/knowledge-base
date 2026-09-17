# P2 历史权限兼容分支收紧测试报告

## 结论

已为检索权限过滤中的“历史缺字段默认放行”逻辑增加可配置收紧开关：

```text
kb.search.legacy-missing-permission-allow=true
```

默认值为 `true`，保持现有兼容行为，不影响尚未完成权限字段回填的历史数据。

当历史数据完成 `acl_tokens`、`visibility`、`visible_unit_codes` 等字段回填并验证后，可设置：

```text
kb.search.legacy-missing-permission-allow=false
```

严格模式下，旧数据如果缺失 `acl_tokens` 且缺失 `visibility`，不会再被当作 PUBLIC/INTERNAL 自动放行。

## 第一性原理校验

权限字段缺失不是一种权限事实。

回填前，为避免历史数据全部不可检索，可以短期兼容放行缺失 `visibility` 的旧文档。

回填后，如果继续把“字段缺失”解释为“允许访问”，本质上是在用数据质量问题制造隐式授权。正确路径应是：

1. 先回填历史权限字段。
2. 验证缺字段数量归零或可解释。
3. 再关闭缺字段兼容放行。

## 代码变更

- `java_service/src/main/java/com/boyang/search/pipeline/steps/EsRecallUtils.java`
  - 新增 `legacyMissingPermissionAllow` 配置。
  - `buildLegacyPermFilter` 中，`metadata.visibility` 缺失放行分支受配置控制。
  - `buildDocSearchPermFilter` 中，顶层 `visibility` 缺失放行分支受配置控制。
- `java_service/src/test/java/com/boyang/search/pipeline/steps/EsRecallUtilsTest.java`
  - 新增 chunk 权限过滤严格模式测试。
  - 新增 `kb_doc_search` 权限过滤严格模式测试。

## 已执行测试

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m'; mvn -Dtest=EsRecallUtilsTest test
```

结果：

```text
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

回归测试：

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m'; mvn -Dtest=IndexAclGuardTest test
```

结果：

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
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

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m'; mvn -Dtest=DocAclProjectionServiceTest test
```

结果：

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 测试过程异常说明

并行运行 Maven 测试时，`IndexAclGuardTest` 命令出现 Surefire fork 启动失败：

```text
Tests run: 0
The forked VM terminated without properly saying goodbye
```

该命令没有进入测试执行阶段。随后顺序单独执行通过，判断为 Windows 本机并行 Maven/JVM 资源竞争问题，不是代码断言失败。

## 切换严格模式前置条件

切换为：

```text
kb.search.legacy-missing-permission-allow=false
```

前必须确认：

1. `kb_document_*` chunk 索引已补齐 `acl_tokens` 或 `metadata.acl_tokens`。
2. `kb_doc_search` 已补齐顶层 `acl_tokens`。
3. 历史数据中仍缺少 `visibility` 的文档数量已归零，或确认这些文档应被拒绝。
4. 普通用户、管理员、无角色用户三类身份完成检索回归。

## 本轮边界

- 本轮只增加收紧开关和单元测试，没有直接切换默认值，避免影响未完成回填的环境。
- 历史数据实际回填仍依赖已有脚本：`ai_service/scripts/backfill_document_permission_projection.py`、`ai_service/scripts/backfill_kb_doc_search.py`、`ai_service/scripts/backfill_qa_source_index.py`。
