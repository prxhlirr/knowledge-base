# P1 任务测试报告：KeywordCoarseEvidenceStep 保留可读物理索引范围

## 1. 任务目标

修复关键词检索证据回查阶段覆盖 `SearchContext.resolvedIndexPattern` 的问题。

在索引级 ACL 场景中，`resolvedIndexPattern` 是当前用户经过角色/用户/部门规则后得到的可读物理索引集合。后续任何检索步骤都不能把它扩大为 `kb_document` 总读别名。

## 2. 问题定位

文件：

- `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordCoarseEvidenceStep.java`

原逻辑：

```java
if (indexPattern != null && indexPattern.contains(",")) {
    if (indexPattern.contains("kb_document_")) {
        indexPattern = "kb_document";
    } else {
        indexPattern = indexPattern.split(",")[0];
    }
}
```

问题：

- 当 `SearchIndexResolver` 输出 `kb_document_policy,kb_document_notice` 时，证据回查会改为查询 `kb_document`。
- `kb_document` 是总读别名，可能覆盖用户无权访问的其他物理索引。
- 这会破坏“角色只能查看某些索引/某些类型文档”的权限边界。

第一性原理结论：

- `KeywordCoarseEvidenceStep` 的职责是补展示证据，不是重新解析权限。
- ES search/msearch 支持逗号分隔的索引表达式。
- 因此该步骤应原样使用 `resolvedIndexPattern`，不应降级为总读别名。

## 3. 本次修改范围

### 3.1 `KeywordCoarseEvidenceStep.java`

新增方法：

- `resolveEvidenceIndexPattern(SearchContext context)`

规则：

1. 优先使用 `context.getResolvedIndexPattern()`。
2. 只有当 resolved 为空时，才回退到 `context.getTenantPolicy().getIndexPattern()`。
3. 多个物理索引的逗号表达式原样保留。
4. 最后兜底为 `kb_document`，保持旧上下文缺失时的兼容性。

执行入口改为：

```java
String indexPattern = resolveEvidenceIndexPattern(context);
```

### 3.2 `KeywordCoarseEvidenceLiteralQueryTest.java`

新增测试：

- `evidenceIndexPatternKeepsResolvedPhysicalIndexScope`
- `evidenceIndexPatternFallsBackToTenantPolicyOnlyWhenResolvedScopeMissing`

保留原有测试：

- `keywordCoarseEvidenceQueryDoesNotUseAnalyzedMatch`
- `keywordBatchedCoarseEvidenceQueryDoesNotUseAnalyzedMatch`

## 4. 验证记录

### 4.1 静态定位

执行命令：

```powershell
rg -n "resolveEvidenceIndexPattern|evidenceIndexPatternKeepsResolvedPhysicalIndexScope|evidenceIndexPatternFallsBack" java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordCoarseEvidenceStep.java java_service/src/test/java/com/boyang/search/pipeline/steps/KeywordCoarseEvidenceLiteralQueryTest.java
```

验证结果：

- `KeywordCoarseEvidenceStep.java:72` 使用 `resolveEvidenceIndexPattern(context)`。
- `KeywordCoarseEvidenceStep.java:1025` 定义索引范围解析方法。
- `KeywordCoarseEvidenceLiteralQueryTest.java:63` 覆盖多物理索引保留场景。
- `KeywordCoarseEvidenceLiteralQueryTest.java:73` 覆盖租户策略兜底场景。

### 4.2 Java 编译验证

执行命令：

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:ReservedCodeCacheSize=64m -XX:CICompilerCount=2 -XX:+UseSerialGC'; mvn -q -DskipTests compile
```

结果：

- 通过。

### 4.3 目标单元测试验证

执行命令：

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:ReservedCodeCacheSize=64m -XX:CICompilerCount=2 -XX:+UseSerialGC'; mvn -q '-Dtest=KeywordCoarseEvidenceLiteralQueryTest,EsRecallUtilsTest,IndexAliasResolverTest,IndexAclSubjectServiceTest' '-DforkCount=0' test
```

结果：

- 通过。

覆盖范围：

- 关键词证据回查不再把多物理索引范围改回 `kb_document`。
- 原有 coarse evidence 不使用 analyzed match 的性能/准确性约束仍保持。
- 前置索引 ACL 解析与 doc_search source_index 解析相关测试继续通过。

## 5. 影响分析

### 5.1 权限正确性

正向影响：

- 关键词文档命中后的证据 chunk 回查，与主召回使用同一可读索引边界。
- 角色只授权 `kb_document_policy` 时，证据回查不会扩大到 `kb_document`。

### 5.2 性能影响

正向或持平：

- 明确物理索引集合通常比 `kb_document` 总读别名命中更少分片。
- 如果 `resolvedIndexPattern` 本身是 `kb_document`，行为不变。

### 5.3 兼容性

兼容：

- 未接入 `SearchIndexResolver` 的旧上下文仍可回退到租户策略索引。
- 租户策略也缺失时仍回退 `kb_document`，避免空索引导致运行时异常。

## 6. 建议后续集成测试

1. 普通角色只授权 `kb_document_policy`，关键词命中文档后，coarse evidence msearch 应只请求 `kb_document_policy`。
2. 普通角色授权 `kb_document_policy,kb_document_notice`，coarse evidence msearch 应保留逗号表达式。
3. 超管解析为 `kb_document` 时，coarse evidence 仍查询总读别名。
4. `resolvedIndexPattern=__no_readable_index__` 的请求应在 `SearchServiceV2` 前置返回空结果，不应进入本步骤。

## 7. 结论

本任务已完成。

关键词证据回查阶段不再扩大索引范围，已与索引级 ACL 的主检索边界保持一致。编译和目标单元测试均通过。
