# P1 任务测试报告：kb_doc_search 接入 source_index 可读索引预筛

## 1. 任务目标

将 `kb_doc_search` 文档级预召回与当前请求的可读 chunk 物理索引范围打通，满足“角色可动态配置可查看某些索引/某些类型文档”的性能路径。

本任务只处理查询侧预筛，不修改数据库 ACL 表结构，不修改 ES 历史数据。

## 2. 本次修改范围

### 2.1 `java_service/src/main/java/com/boyang/search/pipeline/steps/EsRecallUtils.java`

新增：

- `buildDocSearchSourceIndexFilter(String resolvedIndexPattern)`
- `buildReadableSourceIndexValues(String resolvedIndexPattern)`

核心逻辑：

- 当 `resolvedIndexPattern` 是明确的 `kb_document_*` 物理索引列表时，构造 `source_index terms` 过滤。
- 当 `resolvedIndexPattern` 是 `kb_document` 读别名或通配符时，返回 `match_all`，不在 Java 侧猜测 ES alias 展开结果。
- 当历史 `kb_doc_search` 文档没有 `source_index` 字段时放行，由原 `buildDocSearchPermFilter` 和后续 chunk 查询继续兜底。
- 当输入为 `__no_readable_index__` 时，构造不可能命中的哨兵值，避免误放行。

### 2.2 `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordRecallStrategy.java`

调整 `kb_doc_search` 新索引关键词候选召回：

- 将 `SearchContext.resolvedIndexPattern` 解析后的 `indexPattern` 传入 doc_search 查询。
- 在 `buildDocSearchPermFilter(...)` 后叠加 `buildDocSearchSourceIndexFilter(...)`。

效果：

- 文档权限继续由 `acl_tokens / visibility / owner_dept_id` 控制。
- 索引级权限由 `source_index` 控制，减少无权限索引文档进入候选池。

### 2.3 `java_service/src/main/java/com/boyang/search/pipeline/steps/HybridRecallStrategy.java`

调整首页轻量模式 doc_search prefilter：

- 同步路径和并行 `prefilterSources(...)` 路径均传入可读索引范围。
- doc_search 查询叠加 `buildDocSearchSourceIndexFilter(...)`。
- prefilter 缓存 key 增加：
  - `resolvedIndexPattern`
  - `forceSource`

效果：

- 避免同一用户在不同索引权限范围下复用旧候选 source。
- 避免 `data_source` 变化后复用错误缓存。

### 2.4 `java_service/src/test/java/com/boyang/search/pipeline/steps/EsRecallUtilsTest.java`

新增单元测试覆盖：

- 明确物理索引列表可解析为 `source_index` terms 值。
- `kb_document` 读别名和 `kb_document*` 通配符不会被 Java 侧猜测展开。
- `__no_readable_index__` 会生成不可能命中的哨兵值。

## 3. 全流程验证记录

### 3.1 调用点静态检查

执行命令：

```powershell
rg -n "searchDocCandidateSources\(|searchCandidateDocsByTerm\(|buildCandidateDocsRequestItem\(" java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordRecallStrategy.java java_service/src/main/java/com/boyang/search/pipeline/steps/HybridRecallStrategy.java
```

验证结果：

- `KeywordRecallStrategy` 的 `searchCandidateDocsByTerm(...)` 调用和私有方法签名一致。
- `KeywordRecallStrategy` 的 `buildCandidateDocsRequestItem(...)` 调用和私有方法签名一致。
- `HybridRecallStrategy` 的同步 prefilter 路径、并行 prefilter 路径和私有方法签名一致。

### 3.2 Java 编译验证

第一次验证：

```powershell
$env:MAVEN_OPTS='-Xms128m -Xmx384m -XX:ReservedCodeCacheSize=96m'; mvn -q -DskipTests compile
```

结果：

- 失败。
- 失败原因为本机 JVM native memory allocation 失败。
- 失败信息包含：`There is insufficient memory for the Java Runtime Environment to continue`。
- 该失败不是 Java 编译错误，也不是代码断言失败。

第二次验证：

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:ReservedCodeCacheSize=64m -XX:CICompilerCount=2 -XX:+UseSerialGC'; mvn -q -DskipTests compile
```

结果：

- 通过。

### 3.3 目标单元测试验证

执行命令：

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:ReservedCodeCacheSize=64m -XX:CICompilerCount=2 -XX:+UseSerialGC'; mvn -q '-Dtest=EsRecallUtilsTest,IndexAliasResolverTest,IndexAclSubjectServiceTest' '-DforkCount=0' test
```

结果：

- 通过。

覆盖范围：

- 新增的 `source_index` 过滤值解析逻辑。
- 既有索引别名归一化逻辑。
- 既有索引 ACL allow/deny/abstain 决策逻辑。

## 4. 兼容性结论

### 4.1 对已有 `kb_doc_search` 无 `source_index` 数据的影响

不会直接导致旧数据不可检索。

原因：

- 新过滤器对缺失 `source_index` 的文档设置了兼容分支。
- 旧文档仍需通过原有 `buildDocSearchPermFilter(...)`。
- 后续真正取 chunk 内容时仍使用 `resolvedIndexPattern` 查询 chunk 索引，因此不会因为 doc_search 预筛放行旧文档就直接越权返回 chunk。

### 4.2 对新数据的影响

新数据如果已写入 `source_index`：

- 可读索引范围为 `kb_document_policy,kb_document_notice` 时，只召回这两个物理索引对应的 doc_search 文档。
- 可读索引范围为 `kb_document` 或通配符时，不额外收窄，行为与原读别名一致。

### 4.3 性能影响

正向影响：

- 对明确授权到某几个 `kb_document_*` 的角色，doc_search 候选池会提前缩小。
- Hybrid 首页轻量模式 prefilter 缓存更精确，降低权限范围变化后的错误复用风险。

潜在成本：

- 新数据多一个 `source_index terms` filter，属于 keyword 精确过滤，成本低。
- 历史无 `source_index` 数据在回填完成前仍会进入兼容分支，预筛收窄效果不完整。

## 5. 未执行项与原因

未执行全量 `mvn test`。

原因：

- 当前机器此前已多次出现 Maven/JVM native memory 不足。
- 本次并行验证也复现了 native memory allocation 失败。
- 为避免把环境问题误判为代码问题，本次采用目标测试集合验证新增逻辑和相关既有权限逻辑。

建议后续在 CI 或内存更充足的环境执行：

```powershell
mvn test
```

## 6. 边界案例与建议测试用例

建议后续在集成环境补充 ES 级联调用例：

1. 用户角色只允许 `kb_document_policy`，`kb_doc_search` 中同时存在 `source_index=kb_document_policy` 和 `source_index=kb_document_notice`，应只返回 policy 候选。
2. 超管用户读取 `kb_document`，应保持全索引可见。
3. 旧 `kb_doc_search` 文档缺失 `source_index`，但有 `acl_tokens`，应继续按 `acl_tokens` 判权。
4. 旧 `kb_doc_search` 文档缺失 `source_index` 且无 `acl_tokens`，应继续按 `visibility / owner_dept_id` 降级判权。
5. prefilter 缓存中同一用户、同一 query、不同 `resolvedIndexPattern`，应生成不同缓存 key。

## 7. 结论

本任务已完成。

当前代码已经把角色索引权限从 chunk 查询层前移到 `kb_doc_search` 文档预筛层，同时保留历史数据兼容路径。编译和目标测试均通过。
