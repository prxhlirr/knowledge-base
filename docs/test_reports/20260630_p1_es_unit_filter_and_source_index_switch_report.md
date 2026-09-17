# P1 ES 单位过滤与 doc_search.source_index 兼容开关测试报告

## 一、任务目标

基于现有文档入库、检索、权限控制链路，本任务完成两个检索侧优化：

1. `DEPT` 可见范围过滤补充单位编码归一化能力，避免数据库与 JWT 中分别存在 `620102`、`620102000000` 这类行政区划编码形态差异时误过滤。
2. `kb_doc_search` 文档级预召回的 `source_index` 缺失放行策略增加配置开关，支持历史兼容与严格模式灰度切换。

## 二、代码变更

### 1. 单位编码归一化

文件：`java_service/src/main/java/com/boyang/search/pipeline/steps/EsRecallUtils.java`

变更点：

- `buildVisibleUnitValuesForCurrentUser()` 在保留原始 `deptCode` 的基础上，额外加入 `DeptTreeService.normalizeDeptCode(deptCode)` 的结果。
- 过滤值始终包含 `global`，保证公开单位范围文档不被误伤。
- 只在归一化结果非空且不同于原始值时追加，避免重复 terms 值。

第一性原理判断：

- ES 过滤只能基于索引中已有字段做集合匹配。
- 单位权限的核心不是字符串格式本身，而是“同一单位语义是否能被稳定命中”。
- 因此检索阶段同时携带原始编码与标准化编码，是对历史数据、JWT 数据、数据库数据格式不一致的最低成本兼容。

### 2. `source_index` 缺失兼容开关

文件：`java_service/src/main/java/com/boyang/search/pipeline/steps/EsRecallUtils.java`

新增配置：

```properties
kb.search.doc-search-missing-source-index-allow=true
```

默认行为：

- `true`：兼容历史 `kb_doc_search` 中没有 `source_index` 的数据，允许进入后续召回链路。
- `false`：严格模式，不再放行缺失 `source_index` 的历史数据。

第一性原理判断：

- `source_index` 是将“角色可见索引范围”下推到 `kb_doc_search` 文档级预召回的关键字段。
- 但历史数据缺字段时，直接严格过滤会造成旧文档不可检索。
- 因此默认兼容，待离线补齐历史数据后，通过配置切换到严格模式，避免重新发版。

## 三、测试用例

文件：`java_service/src/test/java/com/boyang/search/pipeline/steps/EsRecallUtilsTest.java`

新增/覆盖重点：

1. `visibleUnitValuesIncludeNormalizedAdministrativeDeptCode`
   - 验证当前用户单位为 `620102000000` 时，过滤值同时包含：
     - `global`
     - `620102000000`
     - `620102`

2. `docSearchSourceIndexFilterAllowsMissingSourceIndexByDefault`
   - 验证默认兼容模式下，`buildDocSearchSourceIndexFilter()` 包含 `must_not exists(source_index)` 分支。

3. `strictDocSearchSourceIndexFilterDoesNotAllowMissingSourceIndex`
   - 验证关闭兼容开关后，不再包含缺字段放行分支。
   - 验证严格模式使用不可能命中的 sentinel 条件拒绝缺字段数据。

## 四、执行命令

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:CICompilerCount=2 -XX:+UseSerialGC'; mvn -Dtest=EsRecallUtilsTest -DforkCount=0 test
```

## 五、测试结果

```text
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 六、上线建议

### 阶段 1：兼容上线

保持默认配置：

```properties
kb.search.doc-search-missing-source-index-allow=true
```

目的：

- 不影响历史缺字段文档的现有检索能力。
- 新增 `source_index` 的数据可立即享受索引范围预过滤。

### 阶段 2：历史数据补齐

对 `kb_doc_search` 历史数据补齐 `source_index`。

补齐依据优先级：

1. 数据库文档注册表中的目标索引字段。
2. ES chunk 索引中的 `doc_id -> source_index` 映射。
3. 无法确认来源的文档进入人工核查清单，不应猜测写入。

### 阶段 3：严格模式灰度

完成补齐并抽样验证后切换：

```properties
kb.search.doc-search-missing-source-index-allow=false
```

目的：

- 防止缺 `source_index` 的异常数据绕过角色索引范围配置。
- 将“角色可看哪些索引”的权限约束前移到文档级预召回阶段，提高性能和一致性。

## 七、风险与边界

1. 单位编码归一化只解决同一行政区划编码的格式差异，不负责推导上下级单位链。
2. `source_index` 严格模式依赖历史数据补齐完成，不能直接在生产全量开启。
3. 当 `resolvedIndexPattern` 是 `kb_document` 读别名或通配符时，当前逻辑仍返回 `match_all`，因为 Java 侧不能可靠展开 ES 别名；该场景应依赖 `SearchIndexResolver` 后续继续收窄真实索引范围。
