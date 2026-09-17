# P1 任务测试报告：QA 查询侧接入 source_index 可读索引过滤

## 1. 任务目标

让 QA 召回与主检索的索引级权限边界对齐。

上一批已让新写入的 `kb_qa_pairs` 记录携带 `source_index`。本批将 `SearchContext.resolvedIndexPattern` 从 Java 传给 Python QA 查询接口，并在 Python KNN/BM25 两条 QA 查询路径中按 `source_index` 做兼容过滤。

## 2. 问题定位

当前事实：

- Java `SearchServiceV2` 已经把主检索可读索引范围解析到 `context.resolvedIndexPattern`。
- Java `AiEngineGateway` 之前只传 `acl_tokens`，未传可读物理索引范围。
- Python `/api/ai/qa/search` 和 `/api/ai/qa/search/bm25` 之前只按 `acl_tokens/is_latest/source` 过滤。
- QA 索引是聚合索引，不会天然受 `kb_document_*` 查询范围约束。

第一性原理结论：

- QA 候选属于回答证据候选，必须遵守与主检索一致的文档可见范围。
- 新 QA 数据有 `source_index` 后，应在查询时优先用它收窄候选。
- 历史 QA 缺失 `source_index` 时，不能直接全量丢弃，应继续由 `acl_tokens` 兼容兜底。

## 3. 本次修改范围

### 3.1 `java_service/src/main/java/com/boyang/search/service/SearchServiceV2.java`

修改：

- 在 `startAnswerQaRecall(...)` 中读取 `context.getResolvedIndexPattern()`。
- 调用 QA KNN/BM25 网关时传入该索引范围。

### 3.2 `java_service/src/main/java/com/boyang/search/gateway/AiEngineGateway.java`

修改：

- `fetchQaResults(...)` 增加 `readableSourceIndexPattern` 参数。
- `fetchQaResultsByBm25(...)` 增加 `readableSourceIndexPattern` 参数。
- payload 新增 `readable_source_indexes`。
- 保留旧签名重载，兼容 `QaAnswerService` 等非 SearchContext 路径。

### 3.3 `ai_service/main.py`

修改：

- `QaSearchRequest` 增加 `readable_source_indexes`。
- `Bm25QaSearchRequest` 增加 `readable_source_indexes`。
- 新增 `_readable_qa_source_index_filter(...)`。
- KNN QA 查询 filter 追加 `source_index` 兼容过滤。
- BM25 QA 查询 filter 追加 `source_index` 兼容过滤。

过滤规则：

1. 只接受明确的 `kb_document_*` 物理索引。
2. 遇到 `kb_document` 读别名或通配符时，不在 Python 侧猜测展开。
3. 新 QA 数据命中 `terms(source_index, [...])`。
4. 历史 QA 数据缺少 `source_index` 时兼容放行，继续由 `acl_tokens` 兜底。

## 4. 验证记录

### 4.1 Python 语法检查

执行命令：

```powershell
python -m py_compile ai_service/main.py
```

结果：

- 通过。

### 4.2 Java 编译验证

执行命令：

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:ReservedCodeCacheSize=64m -XX:CICompilerCount=2 -XX:+UseSerialGC'; mvn -q -DskipTests compile
```

结果：

- 首次编译发现 `QaAnswerService` 仍调用旧网关签名。
- 已通过网关方法重载兼容旧签名。
- 复测通过。

### 4.3 目标单元测试

执行命令：

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:ReservedCodeCacheSize=64m -XX:CICompilerCount=2 -XX:+UseSerialGC'; mvn -q '-Dtest=EsRecallUtilsTest,IndexAliasResolverTest,IndexAclSubjectServiceTest,SearchControllerIndexCompatibilityTest,QaVerificationTest' '-DforkCount=0' test
```

结果：

- 通过。

### 4.4 静态核对

执行命令：

```powershell
rg -n "readable_source_indexes|_readable_qa_source_index_filter|source_index|readableSourceIndexPattern|fetchQaResults\(|fetchQaResultsByBm25\(" ai_service/main.py java_service/src/main/java/com/boyang/search/gateway/AiEngineGateway.java java_service/src/main/java/com/boyang/search/service/SearchServiceV2.java
```

结果：

- Java payload 已包含 `readable_source_indexes`。
- SearchServiceV2 已传入 `context.resolvedIndexPattern`。
- Python KNN/BM25 两路 QA 查询均调用 `_readable_qa_source_index_filter(...)`。

## 5. 影响分析

### 5.1 正向影响

- QA 召回不再只依赖 `acl_tokens`，可进一步按角色可读物理索引范围收窄。
- 角色只授权某些 `kb_document_*` 时，新 QA 数据会按 `source_index` 过滤。
- 历史 QA 数据缺字段时不会立即断崖式消失。

### 5.2 兼容性

- 旧 Java 调用签名通过重载保留。
- `QaAnswerService` 等无 SearchContext 的路径继续使用原行为。
- `kb_document` alias 或通配符不会被 Python 猜测展开，避免误过滤。

## 6. 剩余风险

- 历史 QA 缺失 `source_index` 时仍通过兼容分支放行，最终一致性依赖 `acl_tokens`。
- 若要完全按索引级权限收敛，需要执行 QA 历史数据回填。

## 7. 建议后续任务

1. 编写 QA 历史数据回填脚本，从 chunk 或 registry 补齐 `source_index/index_code`。
2. 集成测试：角色只授权 `kb_document_policy` 时，QA KNN/BM25 不返回 `kb_document_notice` 的新 QA 记录。
3. 回填完成后可考虑配置开关，逐步关闭“缺失 source_index 兼容放行”。

## 8. 结论

本任务已完成。

QA 查询侧已接入 `source_index` 可读索引过滤，Java 编译、Python 语法检查和目标 Java 测试均通过。
