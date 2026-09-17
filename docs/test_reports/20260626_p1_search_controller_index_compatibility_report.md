# P1 任务测试报告：SearchController 去除旧物理索引依赖与空指针风险

## 1. 任务目标

修复 `SearchController` 旧接口中两个与多索引/权限改造冲突的问题：

1. `/api/v1/search/analyze` 固定绑定 `kb_document_v1`。
2. `/api/v1/doc/{docId}/chunks` 分片查询分支中存在 `fileName == null && fileName.trim().isEmpty()` 空指针风险。

## 2. 问题定位

文件：

- `java_service/src/main/java/com/boyang/search/controller/SearchController.java`

### 2.1 analyze 固定旧索引

原逻辑：

```java
.index("kb_document_v1")
.analyzer(analyzer)
.text(text)
```

问题：

- `kb_document_v1` 是历史默认物理索引，多索引拆分后不应再作为分词接口依赖。
- 如果 `kb_document_v1` 不存在、空置、mapping 与新索引不一致，前端高亮分词会失败。
- 分词接口的本质需求是“用指定 analyzer 对文本分词”，不是“访问某个文档索引”。

### 2.2 chunks 查询空指针风险

原逻辑：

```java
if (docId != null && !docId.trim().isEmpty()
    && fileName == null && fileName.trim().isEmpty()
    && !"by-file-name".equals(docId)) {
```

问题：

- 当 `fileName == null` 时继续调用 `fileName.trim()`，表达式本身存在 NPE。
- 当前接口前面有 `fileName` 权限校验，部分路径会提前返回，但该查询分支仍是不安全代码。

## 3. 本次修改范围

### 3.1 `SearchController.java`

新增：

- `shouldQueryChunksByDocId(String docId, String fileName)`
- `buildAnalyzeRequest(String text, String analyzer)`
- `isBlank(String value)`
- `isNotBlank(String value)`

修改：

- 分片查询分支改为 `shouldQueryChunksByDocId(docId, fileName)`。
- analyze 请求改为 `buildAnalyzeRequest(text, analyzer)`。
- `buildAnalyzeRequest` 不再设置 index，让 ES 使用集群级 analyzer。

设计结论：

- 分词不绑定任何 `kb_document_*` 物理索引。
- 分片查询分支消除空值表达式风险。
- 不改变现有接口权限准入规则：当前 chunks 接口仍要求 `fileName` 参与 `PermissionGuard` 校验。

### 3.2 `SearchControllerIndexCompatibilityTest.java`

新增轻量单元测试：

- `analyzeRequestDoesNotBindLegacyPhysicalIndex`
- `chunkDocIdBranchIsNullSafeForMissingFileName`

该测试不启动 Spring，不连接 ES，只验证本次新增的纯逻辑。

## 4. 验证记录

### 4.1 Java 编译验证

执行命令：

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:ReservedCodeCacheSize=64m -XX:CICompilerCount=2 -XX:+UseSerialGC'; mvn -q -DskipTests compile
```

结果：

- 通过。

### 4.2 目标单元测试验证

执行命令：

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:ReservedCodeCacheSize=64m -XX:CICompilerCount=2 -XX:+UseSerialGC'; mvn -q '-Dtest=SearchControllerIndexCompatibilityTest,KeywordCoarseEvidenceLiteralQueryTest,EsRecallUtilsTest,IndexAliasResolverTest,IndexAclSubjectServiceTest' '-DforkCount=0' test
```

结果：

- 通过。

覆盖范围：

- analyze 请求不再绑定 `kb_document_v1`。
- docId 分支判断对 `fileName=null` 和空字符串安全。
- 前序索引权限解析、doc_search source_index 解析、关键词证据回查索引范围测试继续通过。

### 4.3 静态扫描

执行命令：

```powershell
rg -n "kb_document_v1" java_service/src/main/java/com/boyang/search/controller/SearchController.java java_service/src/test/java/com/boyang/search/controller/SearchControllerIndexCompatibilityTest.java
rg -n "buildAnalyzeRequest|shouldQueryChunksByDocId" java_service/src/main/java/com/boyang/search/controller/SearchController.java java_service/src/test/java/com/boyang/search/controller/SearchControllerIndexCompatibilityTest.java
```

结果：

- `kb_document_v1` 只剩在注释中描述旧实现，不再作为 analyze 请求 index。
- `SearchController.java:394` 使用 `shouldQueryChunksByDocId(...)`。
- `SearchController.java:582` 使用 `buildAnalyzeRequest(...)`。

## 5. 影响分析

### 5.1 对分词接口的影响

正向影响：

- 不再依赖 `kb_document_v1` 是否存在。
- 多物理索引拆分、alias 灰度切换、v1 下线不会影响分词接口。

注意：

- 该方案要求 `ik_smart/ik_max_word` 是 ES 集群级可用 analyzer。当前系统原本也依赖 IK analyzer，因此这是合理前提。

### 5.2 对分片查询接口的影响

兼容：

- fileName 有值时仍优先按 `metadata.source` 查询。
- docId 为 `by-file-name` 时仍不会进入 doc_id 分支。
- 当前权限准入仍要求 fileName，不在本任务中扩大 docId-only 调用能力。

正向影响：

- 消除查询分支中潜在 NPE。

## 6. 建议后续集成测试

1. 在无 `kb_document_v1` 的 ES 环境调用 `/api/v1/search/analyze`，应正常返回 tokens。
2. 使用 `ik_smart` 和 `ik_max_word` 分别调用 analyze，结果应符合前端高亮预期。
3. 调用 chunks 接口时传入合法 `fileName`，权限校验通过后应返回 coarse chunks。
4. 调用 chunks 接口时缺失 `fileName`，应返回当前既有的 400 权限校验提示，不应抛 NPE。

## 7. 结论

本任务已完成。

`SearchController` 已去除 analyze 对 `kb_document_v1` 的旧物理索引依赖，并修复 chunks 查询分支的空指针风险。编译和目标测试均通过。
