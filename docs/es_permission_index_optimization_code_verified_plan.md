# ES 索引权限与字段优化实施计划（代码验证版）

## 1. 文档边界

本文只纳入已经从现有代码或 live ES 状态验证过的结论。

未经过代码验证的内容不作为实施任务，只作为后续待确认项处理。

验证来源：

1. Java 检索链路代码。
2. Python 入库链路代码。
3. ES live mapping、alias、字段存在率。
4. 已存在的迁移、回填、诊断脚本。

## 2. 已验证的 live ES 现状

### 2.1 当前索引与 alias

已验证当前存在：

```text
kb_doc_meta
kb_doc_meta_v2
kb_doc_search_v1
kb_document_official
kb_document_public
kb_document_law
kb_document_news
kb_document_notice
kb_document_v1
kb_qa_pairs
```

已验证 alias：

```text
kb_document -> kb_document_public / official / law / notice / news / v1
kb_doc_meta_read -> kb_doc_meta_v2
kb_doc_meta_write -> kb_doc_meta_v2
kb_doc_search -> kb_doc_search_v1
kb_doc_search_write -> kb_doc_search_v1
kb_qa_read -> kb_qa_pairs
kb_qa_write -> kb_qa_pairs
```

实施含义：

1. 不能只改 `kb_document_*`，还必须覆盖 `kb_doc_search`、`kb_doc_meta`、`kb_qa`。
2. 普通检索有多物理索引读 alias，角色索引权限如果落地到物理索引，需要查询层显式解析物理索引。

### 2.2 已验证的字段填充现状

已验证：

1. `kb_document_official/public` 中 `metadata.acl_tokens`、`metadata.doc_type`、`metadata.owner_dept_id`、`metadata.visibility`、`metadata.source` 基本有值。
2. `kb_document_official/public` 中 `metadata.dept_l2/l4/l6/l9/dept_code_full` 当前无有效填充。
3. `kb_document_official/public` 中 `metadata.visible_depts` 当前无有效填充。
4. `kb_doc_search_v1` 有 `doc_type`、`acl_tokens`、`owner_dept_id`、`visibility`，但没有 `source_index/index_code/visible_unit_codes`。
5. `kb_doc_meta_v2` 有 `doc_type`、`doc_vector`、`acl_tokens`、`owner_dept_id`、`visibility`，但没有 `source_index/index_code/visible_unit_codes`。
6. `kb_qa_pairs` 有 `acl_tokens`、`source`、`question`、`answer_content`、`question_vector`，但缺少 `source_index/index_code/doc_type/visible_unit_codes`。

实施含义：

1. 现有 `visible_depts` 不能直接作为单位权限依据。
2. `owner_dept_id` 目前大量为 `global`，也不能直接满足单位权限。
3. 要支持角色按索引授权，`kb_doc_search` 和 `kb_qa_pairs` 必须补 `source_index/index_code`。

## 3. 已验证的入库链路

### 3.1 Java 入库会传 `targetIndex`

证据：

- `java_service/src/main/java/com/boyang/search/controller/DocImportController.java`
  - 第 115 行：从请求体读取 `targetIndex`，默认 `kb_document_v1`。
  - 第 198 行：上传接口参数存在 `targetIndex`。
  - 第 214 行：`req.setTargetIndex(targetIndex)`。

### 3.2 Java 入库会根据 `tag` 路由目标索引

证据：

- `java_service/src/main/java/com/boyang/search/strategy/ingest/AbstractIngestStrategy.java`
  - 第 176 行附近注释：`targetIndex` 决策。
  - 第 178 行：如果 `targetIndex` 为空或等于 `kb_document_v1`，按 `tag` 路由。
  - 第 179 行：调用 `docIndexRoutingService.route(req.getTag())`。
  - 第 181 行：`info.put("targetIndex", targetIndex)`。

实施含义：

1. `kb_document_v1` 在现有代码中被当作旧默认值，而不是强制真实目标索引。
2. 新方案必须区分：
   - `requested_target_index`
   - `resolved_target_index`
3. 否则显式选择 `kb_document_v1` 和未选择目标索引无法区分。

### 3.3 Python 入库会再次解析目标索引

证据：

- `ai_service/core/rag_pipeline.py`
  - 第 109 行：`_resolve_target_index(...)`。
  - 第 1175 行：`target_index = self._resolve_target_index(ext_metadata, doc_type)`。
  - 第 1290 行：计算 `_write_alias_name = f"{target_index}_write"`。
  - 第 1293 行：如果写别名存在则使用写别名。
  - 第 1340 行：bulk 写入 `_bulk_target_index`。
  - 第 1599 行：回调 Java 时回传 Python 实际 `targetIndex`。

实施含义：

1. Python 已不是简单写死 `kb_document_v1`。
2. 但 Java 的 `tag` 路由和 Python 的 `doc_type` 路由需要保持一致，否则最终索引可能由 Python 修正。
3. `source_index/index_code` 应由最终 `target_index` 生成，而不是由前端原始参数生成。

### 3.4 `doc_meta/doc_search` 当前没有写入实际目标索引

证据：

- `ai_service/core/indexing/doc_indexer.py`
  - 第 99 行：`update_doc_meta(...)`。
  - 第 144 行附近：写入 `doc_type/data_source/acl_tokens/visibility/owner_dept_id`。
  - 第 175 行：`update_doc_search(...)`。
  - 第 241 行附近：写入 `doc_type/data_source/acl_tokens/visibility/owner_dept_id`。

代码中未发现写入：

```text
source_index
index_code
visible_unit_codes
owner_unit_code
permission_version
```

实施含义：

1. 角色按物理索引授权时，`kb_doc_search` 会成为缺口。
2. `doc_indexer.py` 是必须改造点，不是可选优化。

## 4. 已验证的查询链路依赖

### 4.1 主查询入口已使用 `SearchIndexResolver`

证据：

- `java_service/src/main/java/com/boyang/search/service/SearchServiceV2.java`
  - 第 212 行：`context.setResolvedIndexPattern(searchIndexResolver.resolve(policy, filters))`。
  - 第 217 行：如果为 `__no_readable_index__`，直接返回空。

实施含义：

1. 索引权限入口已经存在。
2. 优先改造 `SearchIndexResolver` 和下游 Step 使用方式，而不是新造一套入口。

### 4.2 `SearchIndexResolver` 已支持显式索引和按过滤路由

证据：

- `java_service/src/main/java/com/boyang/search/service/SearchIndexResolver.java`
  - 第 22 行：`resolve(SysTenantPolicy policy, Map<String, Object> filters)`。
  - 第 44 行：读取 `targetIndex/target_index/index`。
  - 第 53 行：读取 `data_source/tag/doc_type` 做路由。

实施含义：

1. 查询侧已有按索引缩小范围的基础。
2. 角色权限可以接入这里，但必须确认所有召回 Step 不绕开 `resolvedIndexPattern`。

### 4.3 多个召回 Step 已使用 `context.getResolvedIndexPattern()`

证据：

- `SemanticRecallStrategy.java`
  - 第 63 行：读取 `context.getResolvedIndexPattern()`。
  - 第 80、138、179 行：用 `indexPattern` 查询 ES。
- `HybridRecallStrategy.java`
  - 第 79 行：读取 `context.getResolvedIndexPattern()`。
  - 第 94、199、277、332、597 行：使用 `indexPattern` 查询 ES。
- `KeywordRecallStrategy.java`
  - 第 88 行：读取 `context.getResolvedIndexPattern()`。
  - 第 317、366、481 行：使用 `indexPattern`。
- `LiteralRecallStep.java`
  - 第 53 行：读取 `context.getResolvedIndexPattern()`。
  - 第 138、171 行：使用 `indexPattern`。
- `DocExpansionStep.java`
  - 第 67 行：读取 `context.getResolvedIndexPattern()`。
  - 第 113、140 行：使用 `indexPattern`。
- `KeywordCoarseEvidenceStep.java`
  - 第 72 行：读取 `context.getResolvedIndexPattern()`。
  - 第 334、390、440、482、644、677、736 行：使用 `indexPattern`。
- `RerankStep.java`
  - 第 886 行：读取 `context.getResolvedIndexPattern()`。
  - 第 890 行：使用 `indexPattern`。

实施含义：

1. 方案一，即直接查角色允许的物理索引，在主体链路上具备落地基础。
2. 仍需逐个 Step 验证字段路径，因为很多查询条件仍写死 `metadata.*`。

### 4.4 `KeywordCoarseEvidenceStep` 存在回退到 `kb_document` 的风险

证据：

- `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordCoarseEvidenceStep.java`
  - 第 72 行：读取 `resolvedIndexPattern`。
  - 代码中存在多处用 `metadata.source` 关联取证。
  - 之前实读片段显示多物理索引时存在将 `indexPattern` 改回 `kb_document` 的逻辑。

实施含义：

1. 这是角色索引权限的高风险点。
2. 必须在改造任务中显式验证并移除任何“多索引回退 alias”的逻辑。
3. 否则普通召回按物理索引过滤了，取证阶段又可能查回 alias。

## 5. 已验证的旧字段硬依赖

### 5.1 `metadata.source` 是强依赖字段

证据：

- `DocManagementController.java`
  - 第 271 行：按 `metadata.source` 查询。
- `OutboxPoller.java`
  - 第 140 行：按 `metadata.source` 激活版本。
- `DocAclProjectionService.java`
  - 第 20 行注释：chunk 索引通过 `metadata.source` 匹配。
  - 第 86 行：传入 `"metadata.source"`。
- `DocExpansionStep.java`
  - 第 116 行：按 `metadata.source` 扩展 sibling chunk。
- `KeywordRecallStrategy.java`
  - 第 55 行：`DOC_KEY_FIELD = "metadata.source"`。
  - 第 548 行：查询 `metadata.source`。
- `KeywordCoarseEvidenceStep.java`
  - 多处使用 `metadata.source` 关联证据。
- `RerankStep.java`
  - 第 899 行：按 `metadata.source` 回查。
- `KbDocRegistryService.java`
  - 第 85、229 行：按 `metadata.source` 查询或更新。

实施结论：

1. `metadata.source` 不能在第一阶段删除。
2. 即使新增顶层 `source`，也必须双写 `metadata.source`。
3. 查询层迁移到顶层 `source` 前，必须逐个替换上述调用点。

### 5.2 `metadata.doc_type.keyword` 是现有过滤依赖

证据：

- live ES 中 `kb_document_official/public` 的 `metadata.doc_type` 是 `text + keyword`。
- `KeywordRecallStrategy`、`SemanticRecallStrategy`、`HybridRecallStrategy` 仍大量使用 `metadata.*` 字段。

实施结论：

1. 新字段 `doc_type: keyword` 可以加，但旧 `metadata.doc_type.keyword` 必须保留一段时间。
2. 查询过滤必须支持：

```text
doc_type OR metadata.doc_type.keyword
```

### 5.3 `metadata.acl_tokens` 是权限过滤依赖

证据：

- `EsRecallUtils.java`
  - 第 73-78 行：同时查询顶层 `acl_tokens` 和 `metadata.acl_tokens`。
  - 第 83-84 行：旧架构 fallback 判断两个字段是否存在。
- `DocAclProjectionService.java`
  - 第 195-203 行：chunk 授权脚本同时更新顶层和 `metadata.acl_tokens`。

实施结论：

1. 顶层 `acl_tokens` 可以作为新规范。
2. `metadata.acl_tokens` 在 chunk 索引中必须兼容保留，直到 `EsRecallUtils` 和 ACL 投影服务全部切换。

### 5.4 `metadata.search_queries` 不是纯废字段

证据：

- `DocImportController.java`
  - 第 198 行：上传接口接收 `searchQueries`。
  - 第 221 行：设置 `searchQueries`。
- `AbstractIngestStrategy.java`
  - 第 189 行：`info.put("searchQueries", ...)`。
- `DocIngestService.java`
  - 第 365 行：payload 传 `searchQueries`。
- `rag_pipeline.py`
  - 第 1391 行：写入 `metadata.search_queries`。
- `KeywordRecallStrategy.java`
  - 第 379、493 行：source includes 包含 `metadata.search_queries`。
  - 第 560 行：`metadata.search_queries` 参与 matchPhrase 召回。
- `KeywordDocumentMatchStep.java`
  - 第 137、146 行：拼接 `search_queries`。
- `KeywordCoarseEvidenceStep.java`
  - 第 969 行：读取 `metadata.search_queries`。

实施结论：

1. `search_queries` 不能直接删除功能。
2. 可以迁移形态：从每个 chunk 的 `metadata.search_queries` 迁移到 `kb_doc_search_v2.search_text/search_aliases`。
3. 迁移前必须保持旧字段写入和查询兼容。

### 5.5 `colloquial_vector` 已被主链路废弃，但仍有历史脚本

证据：

- `rag_pipeline.py`
  - 第 1443-1444 行：注释标识 `colloquial_vector` 后台回填线程已移除，Java 端检索已废弃。
- `es_setup.py`
  - 第 623-624 行：`colloquial_vector` 改为 `index:false`，注释说明无检索逻辑使用。
- `SimilarityService.java`
  - 第 758、816 行：查询 source 时排除 `colloquial_vector`。
- 仍存在历史脚本：
  - `ai_service/scripts/batch_colloquial_generator.py`
  - `ai_service/tools_and_tests/batch_colloquial_generator.py`

实施结论：

1. 新 template 可以不再定义 `colloquial_vector`。
2. 但删除前需要确认没有定时任务或人工流程仍运行这些回填脚本。
3. 历史脚本应标记废弃或移到归档目录。

## 6. 字段保留/替换结论（代码验证）

### 6.1 必须保留

| 字段 | 结论 | 代码依据 |
|---|---|---|
| `content` | `kb_document_*` 必须保留 | `KeywordRecallStrategy` 第 545 行查询 `content`；`SearchController` 第 470 行读取 `content`；敏感词服务包含 `content` |
| `vector` | `kb_document_*` 必须保留 | `SemanticRecallStrategy` 第 80 行 KNN 使用 `vector`；`HybridRecallStrategy` 多处 KNN 使用 |
| `sparse_vector` | 必须保留 | `SemanticRecallStrategy` rank_features 查询；`AiEngineGateway` 解析 sparse vector |
| `publish_time` | 必须保留 | `DocImportController` 上传参数；`DbInitRunner` registry 有 `publish_time`；`SearchController` 第 484 行展示 |
| `tags` | 必须保留 | `KeywordRecallStrategy` 第 534 行查询 `tags`；`KeywordResultAssembleStep` 第 97-129 行展示 |
| `metadata.source` | 过渡期必须保留 | 多处强依赖，见 5.1 |
| `metadata.acl_tokens` | 过渡期必须保留 | `EsRecallUtils`、`DocAclProjectionService` 依赖 |

### 6.2 可替换但不能立即删除

| 旧字段 | 新字段 | 代码验证结论 |
|---|---|---|
| `metadata.search_queries` | `kb_doc_search_v2.search_text/search_aliases` | 当前仍被关键词召回使用，必须迁移后删除 |
| `metadata.doc_type.keyword` | `doc_type` | 当前索引类型不统一，查询层需双字段 |
| `metadata.owner_dept_id` | `owner_unit_code/visible_unit_codes` | 现有权限过滤使用，且数据大量为 `global`，需新字段补齐后替代 |
| `visible_depts` | `visible_unit_codes` | live 数据无有效填充，但 mapping 和过滤意图存在 |
| `doc_title` | `title` | 结果组装有 fallback，迁移后可去除 |

### 6.3 可进入下线候选

| 字段 | 下线前置条件 |
|---|---|
| `colloquial_vector` | 确认无脚本运行；新 template 不再定义 |
| `metadata.dept_l2/l4/l6/l9/dept_code_full` | `visible_unit_codes` 补齐并查询生效 |
| `metadata.access_groups` | ACL 规则全部由 `acl_tokens` 和 DB 权限承接 |

## 7. 经过代码验证后的必要改造任务

### 7.1 入库字段双写

必须改造：

1. `rag_pipeline.py`
   - 在 chunk 顶层写入 `source/title/doc_type/document_number/publish_time/tags/acl_tokens/is_latest`。
   - 继续写入 `metadata.source/metadata.title/metadata.doc_type/metadata.document_number/metadata.acl_tokens/metadata.is_latest`。
   - 根据最终 `target_index` 写入 `source_index/index_code`。
   - 根据 DB 或传入单位字段写入 `owner_unit_code/visible_unit_codes`。
2. `doc_indexer.py`
   - `update_doc_meta()` 写入 `source_index/index_code/visible_unit_codes/publish_time/security_level`。
   - `update_doc_search()` 写入同样字段，并承接 `search_text/search_aliases`。
3. QA 写入链路
   - `_generate_and_index_qa_pairs()` 或对应 worker 写入 `source_index/index_code/doc_type/visible_unit_codes`。

### 7.2 查询字段双读

必须改造：

1. `KeywordRecallStrategy`
   - `metadata.search_queries` 改为 `search_text/search_aliases` 优先，旧字段兜底。
   - `metadata.source/document_number/title` 新字段优先，旧字段兜底。
2. `SemanticRecallStrategy`
   - BM25 fallback 中 `metadata.title/metadata.document_number` 新字段优先，旧字段兜底。
3. `HybridRecallStrategy`
   - preflight 和 BM25 查询里的 `metadata.source/title/document_number` 新字段优先，旧字段兜底。
   - `docSearchIndex` 查询必须增加 `source_index/index_code` 权限过滤。
4. `LiteralRecallStep`
   - `metadata.document_number/source` 新字段优先，旧字段兜底。
5. `DocExpansionStep`
   - sibling 查询当前按 `metadata.source`，新字段 `source` 生效后双字段查询。
6. `KeywordCoarseEvidenceStep`
   - 所有 `metadata.source` 和 `metadata.doc_id` 关联逻辑双字段兼容。
   - 验证并移除多索引回退 `kb_document` 的逻辑。
7. `RerankStep`
   - 回查 `metadata.source` 双字段兼容。
8. `KeywordResultAssembleStep`
   - 已有 fallback 思路，但需确认新字段优先。

### 7.3 权限过滤改造

必须改造：

1. `EsRecallUtils.buildLegacyPermFilter()`
   - 新增 `visible_unit_codes` 分支。
   - 保留 `metadata.acl_tokens` 和旧 `owner_dept_id` 兜底。
2. `EsRecallUtils.buildDocSearchPermFilter()`
   - 新增 `visible_unit_codes`、`source_index/index_code` 过滤支持。
3. `DocAclProjectionService`
   - ACL 投影继续更新顶层和 `metadata.acl_tokens`。
   - 新增对 `kb_doc_search_v2/kb_doc_meta_v3/kb_qa_pairs_v2` 的权限字段更新。

## 8. 经过代码验证后的分阶段计划

### 阶段 0：补充代码基线

产出：

1. 字段引用清单。
2. 索引 mapping 差异清单。
3. live ES 字段存在率报告。
4. 关键查询 DSL 样本。

不得跳过。

### 阶段 1：只新增字段，不删除字段

原因：

代码中对 `metadata.source`、`metadata.acl_tokens`、`metadata.search_queries` 等字段仍有强依赖。

任务：

1. 新增目标字段 mapping。
2. 新入库双写。
3. 历史数据先补新字段。

不做：

1. 不删除 `metadata.source`。
2. 不删除 `metadata.acl_tokens`。
3. 不删除 `metadata.search_queries`。
4. 不删除 `metadata.doc_type`。

### 阶段 2：查询双读兼容

任务：

1. 所有查询新字段优先。
2. 旧字段 fallback。
3. 对 fallback 命中打日志。

验收：

1. 旧索引可检索。
2. 新索引可检索。
3. fallback 命中可观测。

### 阶段 3：聚合索引补权限维度

必须先做：

1. `kb_doc_search_v2` 补 `source_index/index_code/visible_unit_codes`。
2. `kb_doc_meta_v3` 补同样字段。
3. `kb_qa_pairs_v2` 补同样字段。

原因：

代码中 `KeywordRecallStrategy` 和 `HybridRecallStrategy` 都会查询 `docSearchIndex`。如果 `kb_doc_search` 没有 `source_index/index_code`，角色按索引授权会在文档级预检索阶段失效。

### 阶段 4：迁移历史数据

迁移策略：

1. 从 DB `kb_doc_registry.target_index/dept_code/publish_time/tags/doc_number` 补字段。
2. 从旧 ES 补 vector、content、question_vector 等不可重算字段。
3. 用 scroll + bulk，不使用纯 `_reindex`。

原因：

迁移过程中需要 DB join、字段重命名、单位权限计算，纯 `_reindex` 无法满足。

### 阶段 5：alias 灰度切换

切换顺序：

1. `kb_doc_search`。
2. `kb_doc_meta_read`。
3. `kb_qa_read`。
4. chunk 写别名。

保留：

1. `kb_document` alias 作为回滚路径。
2. 旧索引只读。

### 阶段 6：旧字段下线

下线条件：

1. 代码中无旧字段引用。
2. fallback 日志连续观察期为 0。
3. 历史脚本确认停用。
4. 新索引功能回归通过。

## 9. 上一版方案中需要修正的点

### 9.1 不能说 `search_queries` 可直接清理

修正：

`search_queries` 当前确实参与关键词召回。只能迁移到文档级索引的 `search_text/search_aliases` 后再清理 chunk 字段。

### 9.2 不能只说保留 `metadata.*` 作为兼容

修正：

`metadata.source`、`metadata.acl_tokens` 当前是强依赖字段，必须列为第一阶段必保字段。

### 9.3 不能把 `kb_qa_pairs` 放到后置可选

修正：

只要 QA 参与召回，`kb_qa_pairs` 必须同步接入权限字段。否则存在 QA 越权召回风险。

### 9.4 不能直接建议删除 `visible_depts`

修正：

live 数据中它当前无有效填充，但 mapping 和旧权限意图存在。正确做法是新增 `visible_unit_codes`，查询层先双读，确认无旧字段依赖后再下线。

## 10. 后续待验证项

以下内容尚未在当前代码扫描中完全验证，不能直接实施：

1. 是否存在定时任务运行 `batch_colloquial_generator.py`。
2. 前端是否依赖 `doc_title` 字段展示。
3. `SearchController` 旧接口是否仍被生产流量调用。
4. `SimilarityService` 所有 fallback 查询是否都接入 `resolvedIndexPattern`。
5. 当前 DB 中 `kb_doc_registry.dept_code/unit/publish_time/tags` 的真实填充率。
6. `security_level` 是否已有权限策略实际使用。

这些项必须在实施前补充验证。
