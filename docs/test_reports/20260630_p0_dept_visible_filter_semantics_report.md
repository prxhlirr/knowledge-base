# P0 单位权限过滤语义修复测试报告

## 结论

已修复 ES 前置权限过滤中过度应用 `visible_unit_codes` 的问题。现在单位链过滤只对 `visibility=DEPT` 的文档生效，PUBLIC/INTERNAL/PRIVATE/GRANT 不再被单位字段额外误伤，继续由 `acl_tokens` 与 MySQL 后置权限校验决定。

## 修复原因

原逻辑在 `buildLegacyPermFilter` 和 `buildDocSearchPermFilter` 外层无条件追加 `visible_unit_codes` 过滤。这样会导致：

- INTERNAL 文档如果带了单位字段，可能不再对所有登录用户可见。
- PRIVATE 文档上传者如果不在归属单位链上，可能搜不到自己的文档。
- GRANT/角色授权文档即使命中 `role::xxx`，也可能被单位字段挡掉。

从权限模型第一性原理看，单位链是 DEPT 可见性的判断条件，不应成为所有 visibility 的全局硬约束。

## 代码变更

- `java_service/src/main/java/com/boyang/search/pipeline/steps/EsRecallUtils.java`
  - `buildVisibleUnitFilter` 改为两分支：
    - 非 DEPT 文档：不要求命中 `visible_unit_codes`。
    - DEPT 文档：必须命中 `visible_unit_codes` 或 `metadata.visible_unit_codes`。
  - chunk 主索引用 `metadata.visibility`。
  - `kb_doc_search` 用顶层 `visibility`。
- `java_service/src/test/java/com/boyang/search/pipeline/steps/EsRecallUtilsTest.java`
  - 增加 `metadata.visibility=DEPT` DSL 断言。
  - 增加顶层 `visibility=DEPT` DSL 断言。

## 已执行测试

首次并行执行两个 Maven 测试时触发 JVM native memory 不足：

```text
There is insufficient memory for the Java Runtime Environment to continue.
Native memory allocation failed
```

该失败由并行 Maven 编译导致，不是代码断言失败。随后改为单进程并限制 JVM 内存后顺序执行。

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

## 覆盖范围

- chunk 主索引权限过滤仍包含 `visible_unit_codes`。
- chunk 主索引权限过滤仍包含 `metadata.visible_unit_codes`。
- chunk 主索引单位过滤绑定 `metadata.visibility=DEPT`。
- `kb_doc_search` 权限过滤只使用顶层 `visible_unit_codes`。
- `kb_doc_search` 单位过滤绑定顶层 `visibility=DEPT`。
- 恢复 payload ACL token 修复未被本次变更破坏。

## 剩余风险

- 如果业务最终定义为“任何文档只要挂单位，都必须单位内可见”，则本次语义需要调整。但这会与 PRIVATE/GRANT/角色授权产生冲突，必须重新定义权限优先级。
- QA 检索当前只做 `acl_tokens/source_index` 过滤，尚未按 DEPT 语义使用 `visible_unit_codes`，后续应在 QA 权限投影同步任务中一起处理。
