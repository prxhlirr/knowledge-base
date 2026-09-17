# ES 索引权限与字段优化实施文档（代码/环境验证版）

生成时间：2026-06-25

## 1. 结论摘要

本方案只基于已验证的代码、配置文件与当前可连通 ES 状态给出实施任务。未能通过代码或当前环境确认的内容，不作为既定事实，只列入上线前门禁。

### 1.1 已验证结论

| 结论 | 验证依据 |
| --- | --- |
| 当前系统已有“索引级权限入口”，可支持某个/某几个角色查看某些物理索引。 | `IndexAclSubjectService`、`IndexAclGuard`、`IndexAclSubjectsController` 已存在；`SearchServiceV2` 会通过 `SearchIndexResolver.resolve()` 设置 `resolvedIndexPattern`。 |
| 当前系统已有“文档级权限入口”，ES 侧用 `acl_tokens` 加速，MySQL 侧用 `PermissionGuard` 做后置强校验。 | `EsRecallUtils.buildLegacyPermFilter()`、`buildDocSearchPermFilter()`；`SearchServiceV2.applyPostPermissionFilter()`；`PermissionGuard.canAccess()`。 |
| “文档挂 A 部门，则 A 部门及上级部门可见”在入库 token 计算上已有设计。 | `DocIngestService.computeAclTokens()` 中 DEPT 模式写当前部门及祖先部门 token。 |
| “管理员查看全部文档”已有两层旁路。 | `EsRecallUtils` 遇 `_SUPER_ADMIN` 直接 `match_all`；`PermissionGuard` 遇 `identity.isSuperAdmin()` 直接放行。 |
| “角色查看某类文档”有两种实现入口：索引级 ACL 控制物理索引，文档级 `role::` token 控制具体文档。 | `IndexAclSubjectService.principalKeys()` 支持 `ROLE::`；`DocIngestService.computeAclTokens()` 的 GRANT 模式写 `role::roleCode`。 |
| 当前 ES 读别名 `kb_document` 已挂多个物理索引，因此可在查询时解析成物理索引集合。 | 当前 ES `_alias`：`kb_document` 指向 `kb_document_public/official/law/notice/news/v1`。 |
| 当前环境不存在 `kb_doc_qa`，实际问答索引是 `kb_qa_pairs`，读别名是 `kb_qa_read`。 | 当前 ES `_cat/indices` 与 `_alias/kb_qa_read`。 |
| `security_level` 已在 schema/mapping 中出现，但没有进入实际检索权限过滤链路。 | 代码搜索仅命中 schema、entity、样例 mapping；未命中检索 DSL 使用。 |
| `search_queries` 当前在入库和检索中均被使用，不能直接删除。 | `DocImportController` 接收；`AbstractIngestStrategy`、`DocIngestService`、`rag_pipeline.py` 写入；`KeywordRecallStrategy`、`KeywordDocumentMatchStep`、`KeywordCoarseEvidenceStep` 读取。 |
| `colloquial_vector` 主链路已废弃，但仓库中仍有历史脚本会写入，必须做脚本治理。 | `rag_pipeline.py` 注释标明 Java KNN 已废弃；`SimilarityService` 仅排除该字段；`scripts/batch_colloquial_generator.py`、`ai_service/scripts/rag_pipeline.py` 仍会写。 |

### 1.2 当前主要缺陷

| 缺陷 | 影响 | 处理优先级 |
| --- | --- | --- |
| `kb_index_acl_subjects` 建表 SQL 位于 `db/offline/004_index_acl_subjects.sql`，未进入 `db/migration` 主链路。 | 索引级 ACL 代码存在，但目标环境可能没有表。 | P0 |
| `SearchServiceV2` 后置权限注释说按 `doc_id`，实际代码取 `file_name/organization` 作为 guardKey。 | 文档唯一键语义不一致，同名文件/机构名场景存在误判风险。 | P0 |
| `SimilarityService` 默认仍查 `editor.similarity.chunk-fallback-index=kb_document`。 | 主检索限定索引后，相似推荐仍可能扫全读别名。 | P0 |
| 旧 `SearchController` 仍有固定 `kb_document_v1` 的 analyze 调用，并且分片查询条件存在空指针风险。 | 多索引迁移后 analyze 依赖旧索引；分片查询稳定性受影响。 | P1 |
| 当前 ES mapping 不一致：`kb_document_public` 无顶层 `acl_tokens`，`kb_document_official` 有顶层 `acl_tokens` 但类型为 `text + keyword`。 | 当前兼容逻辑可工作，但新索引应统一为 `keyword`，避免权限 terms 查询字段不稳定。 | P1 |
| `kb_doc_search_v1`、`kb_doc_meta_v2` 当前没有 `source_index/index_code`。 | 无法从聚合索引直接判断原文档所在物理索引。 | P1 |
| 当前 `kb_qa_pairs` 只有 `acl_tokens/question_vector` 有数据，`owner_dept_id/security_level/visible_depts/vector` 当前填充为 0。 | QA 召回若要接入单位/角色权限，需要补齐权限投影字段或统一依赖 `acl_tokens`。 | P1 |

## 2. 当前 ES 状态快照

### 2.1 索引与别名

当前 ES 可连通 `localhost:9200`，验证结果：

| 索引 | 文档数 | 备注 |
| --- | ---: | --- |
| `kb_document_official` | 669 | `kb_document` 读别名成员 |
| `kb_document_public` | 22 | `kb_document` 读别名成员 |
| `kb_document_law` | 0 | `kb_document` 读别名成员 |
| `kb_document_notice` | 0 | `kb_document` 读别名成员 |
| `kb_document_news` | 0 | `kb_document` 读别名成员 |
| `kb_document_v1` | 0 | `kb_document` 读别名成员，历史默认索引 |
| `kb_doc_meta` | 13 | 老 meta 索引 |
| `kb_doc_meta_v2` | 5 | `kb_doc_meta_read` 指向该索引 |
| `kb_doc_search_v1` | 21 | `kb_doc_search` 指向该索引 |
| `kb_qa_pairs` | 419 | `kb_qa_read` 指向该索引 |

### 2.2 关键字段填充情况

| 索引 | 总数 | 已填充字段 |
| --- | ---: | --- |
| `kb_document_public` | 22 | `metadata.acl_tokens=22`、`metadata.owner_dept_id=22`、`metadata.title=22`、`vector=22` |
| `kb_document_official` | 669 | `acl_tokens=96`、`metadata.acl_tokens=669`、`metadata.owner_dept_id=669`、`metadata.title=669`、`vector=669` |
| `kb_doc_search_v1` | 21 | `acl_tokens=21`、`doc_title=1`、`owner_dept_id=21`、`security_level=1` |
| `kb_doc_meta_v2` | 5 | `acl_tokens=5`、`owner_dept_id=5`、`doc_vector=5` |
| `kb_qa_pairs` | 419 | `acl_tokens=419`、`question_vector=419` |

## 3. 字段保留/清理决策

### 3.1 必须保留

| 字段 | 决策 | 已验证原因 |
| --- | --- | --- |
| `publish_time` | 保留 | `KeywordResultAssembleStep`、`KeywordRecallStrategy`、`RerankStep`、`LiteralRecallStep` 会读取或返回该字段。 |
| `content` | 保留 | BM25、原文预览、证据片段、相似推荐均依赖正文。 |
| `vector` | 保留 | 语义召回、相似推荐、证据补充依赖 KNN。 |
| `doc_title` / `metadata.title` | 保留，但统一语义 | 标题检索、排序文本、结果展示均使用。 |
| `tags` / `tags_kw` | 保留，但拆分 text/keyword 语义 | `tags_kw` 用于精确过滤/候选文本，`tags` 用于展示或文本召回。 |
| `acl_tokens` / `metadata.acl_tokens` | 保留并统一为权限加速投影字段 | ES 前置权限过滤核心字段。 |
| `owner_dept_id` / `metadata.owner_dept_id` | 保留 | 当前文档/聚合索引已有填充，旧权限降级和结果展示会用。 |
| `search_queries` / `metadata.search_queries` | 保留 | 当前代码明确写入并参与关键词召回。 |

### 3.2 不纳入新 mapping

| 字段 | 决策 | 已验证原因 |
| --- | --- | --- |
| `colloquial_vector` | 新索引不再定义；历史数据不强制清理 | 主链路检索已废弃；但历史脚本仍存在，先禁止运行/归档脚本，再通过重建索引自然淘汰。 |
| `security_level` | 暂不作为权限字段上线 | 当前没有实际过滤代码；如果上线，需要补 `SysTenantPolicy.minSecurityLevel` 到 ES 查询 DSL。 |
| `visible_depts` | 暂不作为主权限字段 | 当前权限模型已选择 `acl_tokens`，且现有 ES 填充率为 0 或无实际使用。可保留为审计/兼容字段，但不要依赖它做主判断。 |

## 4. 推荐目标架构

### 4.1 权限分层

采用“三层过滤”：

1. 租户级：`sys_tenant_policy.allowed_indices` 限定 appCode 最大可读索引范围。
2. 索引级：`kb_index_acl_subjects` 限定角色/用户/部门可读哪些物理索引。
3. 文档级：`acl_tokens` 在 ES 召回阶段加速过滤，`PermissionGuard` 在 MySQL 后置强校验。

### 4.2 单位权限模型

已验证代码中 DEPT token 设计为：

1. 文档挂 A 部门：入库时写 `dept::A` 以及 A 的所有祖先部门 token。
2. 用户属于 U 部门：请求身份中携带用户部门及祖先部门 token。
3. ES 前置过滤：文档 `acl_tokens` 与用户 token 求交集，命中即通过。
4. MySQL 后置过滤：`PermissionGuard` 使用 `DeptTreeService.isSubDept(docDept, userDept)` 做强校验。

该模型满足“文档挂在 A 部门，则 A 部门及上级部门可见该文档”。注意：这里的判断方向是“用户部门是否为文档部门的祖先或同级”，当前代码注释与 token 计算已经按该方向设计。

### 4.3 角色看索引/文档类型

推荐优先使用物理索引表达“文档类型大类”，再用 `kb_index_acl_subjects` 做角色授权：

| 需求 | 实现 |
| --- | --- |
| 角色 R 可看 `kb_document_public`、`kb_document_law` | 写两条 `kb_index_acl_subjects`：`subject_type=ROLE`、`subject_value=R`、`index_name=...`、`scope=READ`、`effect=ALLOW`。 |
| 角色 R 可看某些细粒度文档类型，但这些类型共用同一物理索引 | 使用文档级 GRANT/role token，或新增更细物理索引路由。 |
| 管理员看全部索引 | 身份注入 `_SUPER_ADMIN` 或 `isSuperAdmin=true`，ES 与 MySQL 均旁路。 |

## 5. 目标 Mapping 方案

### 5.1 `kb_document_*` chunk 索引

所有新建 chunk 索引按以下 mapping 统一：

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `content` | `text`, `ik_max_word` / `ik_smart` | 正文检索、证据抽取 |
| `display_content` | `text` + `keyword` | 展示、兼容 |
| `vector` | `dense_vector(1024)`, `index=true`, `cosine` | 语义召回 |
| `sparse_vector` | `rank_features` | 稀疏召回 |
| `keywords` | `keyword` | 精确词 |
| `chunk_granularity` | `keyword` | fine/coarse 过滤 |
| `parent_chunk_id` | `keyword` | chunk 关联 |
| `acl_tokens` | `keyword` | 权限前置过滤，新增索引必须统一顶层 |
| `metadata.acl_tokens` | `keyword` | 兼容当前主链路与历史数据 |
| `metadata.source` | `keyword` | 文档定位；当前大量代码依赖 |
| `metadata.doc_id` | `keyword` | 文档唯一键；用于修正当前 text+keyword 不一致 |
| `metadata.doc_type` | `keyword` + 可选 `text` 子字段 | 类型过滤与类型检索 |
| `metadata.title` | `text` + `keyword` | 标题检索/展示 |
| `metadata.document_number` | `keyword` + 可选 ngram/text | 文号检索 |
| `metadata.tags` | `text` | 标签文本召回 |
| `metadata.tags_kw` | `keyword` | 标签精确过滤 |
| `metadata.search_queries` | `text` | 搜补关键词 |
| `metadata.publish_time` | `date` | 时间展示/排序 |
| `metadata.owner_dept_id` | `keyword` | 单位/部门归属 |
| `metadata.dept_code_full`、`dept_l2/l4/l6/l9` | `keyword` | 历史兼容/分析维度 |
| `metadata.visibility` | `keyword` | 旧权限模型兼容 |
| `metadata.uploader_id` | `keyword` | PRIVATE/上传者兼容 |
| `metadata.is_latest` | `boolean` | 版本过滤 |
| `metadata.doc_version` | `integer` | 版本号 |
| `metadata.chunk_id` | `integer` | 排序 |
| `metadata.dynamic_meta` | `object`, `dynamic=false` | 扩展元数据 |

不再新增：`colloquial_vector`。

### 5.2 `kb_doc_search_v1` 文档级检索索引

保留并补充：

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `source` / `source_name` | `keyword` | 与 chunk/meta 对齐 |
| `doc_id` | `keyword` | 文档唯一键 |
| `source_index` | `keyword` | 新增：原物理索引 |
| `doc_type` | `keyword` | 类型过滤 |
| `title` / `doc_title` | `text` + `keyword` | 标题检索 |
| `content` / `doc_terms` / `summary` | `text` | 文档级粗召回 |
| `vector` | `dense_vector(1024)` | 文档级语义召回 |
| `acl_tokens` | `keyword` | 权限过滤 |
| `owner_dept_id` | `keyword` | 单位归属 |
| `visibility` | `keyword` | 兼容 |
| `publish_time` | `date` | 排序/展示 |
| `tags` / `keywords` / `entities` | `keyword` | 精确过滤/辅助召回 |
| `is_latest` | `boolean` | 版本过滤 |
| `security_level` | `integer` | 暂保留，不启用过滤 |

### 5.3 `kb_doc_meta_v2` 文档向量/相似索引

保留并补充：

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `source` / `source_name` | `keyword` | 文档定位 |
| `doc_id` | `keyword` | 文档唯一键 |
| `source_index` | `keyword` | 新增：用于按可读索引约束相似推荐 |
| `doc_type` | `keyword` | 类型过滤 |
| `doc_title` / `title` | `text` + `keyword` | 展示/召回 |
| `doc_vector` | `dense_vector(1024)` | 文档相似 |
| `acl_tokens` | `keyword` | 权限过滤 |
| `owner_dept_id` | `keyword` | 单位归属 |
| `visibility` | `keyword` | 兼容 |
| `is_latest` | `boolean` | 版本过滤 |

### 5.4 `kb_qa_pairs`

当前环境实际问答索引为 `kb_qa_pairs`。如 QA 召回接入同一权限体系：

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `question` | `text` | 问题召回 |
| `answer_content` | `text` | 答案展示 |
| `question_vector` | `dense_vector(1024)` | QA 语义召回 |
| `source` | `keyword` | 文档定位 |
| `answer_chunk_id` | `keyword` | chunk 关联 |
| `doc_version` | `integer` | 版本 |
| `is_latest` | `boolean` | 最新版本过滤 |
| `acl_tokens` | `keyword` | 必须统一为 keyword；当前 mapping 是 text+keyword，需要新索引修正 |
| `source_index` | `keyword` | 新增：原物理索引 |
| `owner_dept_id` | `keyword` | 可选补充，当前填充为 0 |

## 6. 实施任务清单

### P0：权限正确性前置任务

| 编号 | 任务 | 涉及文件/模块 | 验收 |
| --- | --- | --- | --- |
| P0-1 | 将 `kb_index_acl_subjects` 建表纳入正式 migration，不能只放 `db/offline`。 | `java_service/src/main/resources/db/offline/004_index_acl_subjects.sql` → 新增 migration | 新环境启动后自动存在表与索引。 |
| P0-2 | 修正后置权限主键语义：统一使用 `kb_doc_registry.source_name` 或稳定 `doc_id`，注释与代码一致。 | `SearchServiceV2.applyPostPermissionFilter()`、`PermissionGuard`、`KbDocRegistryService` | 同名文件/同机构多文档场景权限不串。 |
| P0-3 | `SimilarityService` 接入 `SearchIndexResolver` 或 `IndexAclGuard`，不能固定扫 `kb_document`。 | `SimilarityService.searchEditorSimilarChunks()`、`fetchBestChunkEvidence()` | 角色只授权某物理索引时，相似推荐只返回该索引下文档。 |
| P0-4 | 确认运行 profile 与数据库类型，统一 migration 语法。 | `application.yml`、`application-dev.yml`、`application-prod.yml` | MySQL/PostgreSQL 环境均有明确脚本，不混用 `public.` schema。 |

### P1：索引与检索一致性任务

| 编号 | 任务 | 涉及文件/模块 | 验收 |
| --- | --- | --- | --- |
| P1-1 | 更新 `kb_document_template`：去掉 `colloquial_vector`，统一 `acl_tokens=keyword`，`metadata.doc_id=keyword`，保留当前已用字段。 | `ai_service/core/indexing/es_setup.py`、`ai_service/core/rag_pipeline.py` | 新建 `kb_document_*` mapping 一致。 |
| P1-2 | `doc_indexer.py` 写入 `source_index` 到 `kb_doc_search` 与 `kb_doc_meta`。 | `ai_service/core/indexing/doc_indexer.py` | 聚合索引可直接按原物理索引过滤。 |
| P1-3 | `SearchIndexResolver` 输出的物理索引集合同步用于 doc_search/doc_meta/qa 召回过滤。 | `KeywordRecallStrategy`、`HybridRecallStrategy`、`SimilarityService`、QA 召回模块 | 主召回、文档级预筛、相似推荐、QA 权限范围一致。 |
| P1-4 | 修复旧 `SearchController` 固定 `kb_document_v1` 的 analyze 策略。 | `SearchController.analyzeQuery()` | `kb_document_v1` 不存在或空索引时 analyze 仍可用。 |
| P1-5 | 修复 `SearchController` 分片查询空指针条件。 | `SearchController` doc chunks 查询分支 | `docId` 有值、`fileName` 为空时不抛 NPE。 |
| P1-6 | 归档或加保护历史 `colloquial_vector` 脚本。 | `scripts/batch_colloquial_generator.py`、`ai_service/scripts/batch_colloquial_generator.py`、`ai_service/scripts/rag_pipeline.py` | 默认部署/运维脚本不会再写 `colloquial_vector`。 |

### P2：历史数据迁移任务

| 编号 | 任务 | 涉及索引 | 验收 |
| --- | --- | --- | --- |
| P2-1 | 新建版本化索引和模板，如 `kb_document_official_v2`、`kb_doc_search_v2`、`kb_doc_meta_v3`、`kb_qa_pairs_v2`。 | ES | mapping 与目标方案一致。 |
| P2-2 | reindex chunk 索引，补齐顶层 `acl_tokens`、修正字段类型，去掉 `colloquial_vector`。 | `kb_document_*` | 新索引 count 与旧索引一致，权限抽样一致。 |
| P2-3 | 从 chunk 或数据库重建 `kb_doc_search`、`kb_doc_meta`，补 `source_index`。 | `kb_doc_search_v1`、`kb_doc_meta_v2` | 每个文档级记录能定位原物理索引。 |
| P2-4 | QA 索引重建或补充 `source_index/acl_tokens.keyword`。 | `kb_qa_pairs` | QA 召回权限与主检索一致。 |
| P2-5 | 原子切换别名并保留回滚窗口。 | `kb_document`、`kb_doc_search`、`kb_doc_meta_read`、`kb_qa_read` | 切换前后核心检索结果可解释，无重复命中新旧索引。 |

## 7. 历史数据单位字段离线补充方案

已经入库的文档可以基于数据库单位字段单独补充，但必须以数据库为权威源，ES 只做投影。

推荐流程：

1. 从 `kb_doc_registry` 读取 `source_name`、`dept_code`、`visibility`、`target_index`。
2. 对每条文档用 `DeptTreeService.buildAclChain(dept_code)` 生成 `dept::` token。
3. 根据 `visibility` 合并 `_PUBLIC/_INTERNAL/user::/role::` 等 token。
4. 对 chunk 索引用 `target_index + metadata.source` 做 `update_by_query`，同时更新顶层 `acl_tokens` 与 `metadata.acl_tokens`。
5. 对 `kb_doc_search_write`、`kb_doc_meta_write` 用 `source` 更新顶层 `acl_tokens/owner_dept_id/source_index`。
6. 对 `kb_qa_pairs` 用 `source` 更新 `acl_tokens/source_index/owner_dept_id`。
7. 更新后抽样跑 `PermissionGuard.batchCheck()` 与 ES 查询结果对账。

该流程与现有 `DocAclProjectionService` 的投影思路一致；不同点是需要批量补 `source_index` 和单位 token。

## 8. 测试与验收

### 8.1 单元/集成测试

| 场景 | 预期 |
| --- | --- |
| 普通角色只授权 `kb_document_public` | `resolvedIndexPattern` 只含 `kb_document_public`。 |
| 普通角色授权多个索引 | 主检索、doc_search 预筛、相似推荐均只访问授权索引。 |
| 未授权角色访问受限索引 | 返回空结果，`timings.index_acl_denied=1` 或无命中。 |
| 超管身份 | ES 前置与 MySQL 后置均放行。 |
| 文档挂子部门 A | A 部门及上级部门用户可见，平级/下级非相关部门不可见。 |
| `kb_qa_pairs` 召回 | QA 结果不能越过主文档权限。 |

### 8.2 ES 验证命令

上线前至少验证：

```bash
GET _alias/kb_document,kb_doc_search,kb_doc_meta_read,kb_qa_read
GET kb_document_*/_mapping
GET kb_doc_search*/_mapping
GET kb_doc_meta*/_mapping
GET kb_qa_pairs*/_mapping
```

并对核心字段做 exists 统计：

```bash
POST kb_document/_count {"query":{"exists":{"field":"metadata.acl_tokens"}}}
POST kb_document/_count {"query":{"exists":{"field":"acl_tokens"}}}
POST kb_doc_search/_count {"query":{"exists":{"field":"source_index"}}}
POST kb_doc_meta_read/_count {"query":{"exists":{"field":"source_index"}}}
POST kb_qa_read/_count {"query":{"exists":{"field":"acl_tokens"}}}
```

## 9. 上线门禁

以下不是建议项，而是当前代码/环境尚不能直接证明的上线前必检项：

1. 确认生产数据库类型与 migration 执行器，解决 `application.yml` 默认 MySQL、dev/prod PostgreSQL 的差异。
2. 确认生产环境是否仍有定时任务或人工运维脚本运行 `batch_colloquial_generator.py` 或旧 `scripts/rag_pipeline.py`。
3. 确认前端/外部系统是否仍调用旧 `SearchController` 分片与 analyze 接口。
4. 确认 `kb_doc_registry` 中 `source_name` 是否能唯一定位文档；如不能，必须先引入稳定 `doc_id` 作为权限主键。
5. 确认 `kb_index_acl_subjects` 在目标环境实际存在，且 `IndexAclSubjectsController` 的内部 token 管控满足生产安全要求。

## 10. 推荐实施顺序

1. 先做 P0-1 到 P0-4，确保权限表、权限主键、相似推荐和数据库迁移链路正确。
2. 再做 P1-1 到 P1-6，统一新索引 mapping 与所有检索入口。
3. 用小批量影子索引执行 P2-1 到 P2-4，完成历史数据迁移演练。
4. 灰度切换别名，观察后置权限拦截数、空结果率、索引访问范围、查询耗时。
5. 稳定后清理旧索引和历史脚本入口。

## 11. 代码证据索引

本节用于说明上文任务不是推测，而是由代码或当前 ES 状态直接推导。

### 11.1 索引级 ACL

| 结论 | 证据 |
| --- | --- |
| 系统已有索引级 ACL 服务。 | `java_service/src/main/java/com/boyang/search/service/IndexAclSubjectService.java:25` 定义服务；`:78` 决策入口；`:107` 组装用户/角色/部门 principal。 |
| 索引 ACL 规则变更会触发策略版本。 | `IndexAclSubjectService.java:57`、`:70` 调用 `policyVersionService.bumpGlobalVersion(...)`。 |
| 检索时会展开 `kb_document` 读别名并过滤可读物理索引。 | `java_service/src/main/java/com/boyang/search/service/IndexAclGuard.java:29`、`:34`、`:57`、`:69`。 |
| 没有可读索引时会返回特殊空索引。 | `IndexAclGuard.java:44` 返回 `__no_readable_index__`；`SearchServiceV2.java:217` 检测后直接空结果。 |
| 主检索已经接入索引解析。 | `java_service/src/main/java/com/boyang/search/service/SearchServiceV2.java:212` 设置 `resolvedIndexPattern`。 |

### 11.2 文档级权限

| 结论 | 证据 |
| --- | --- |
| ES 前置权限过滤查 `acl_tokens` 与 `metadata.acl_tokens`。 | `java_service/src/main/java/com/boyang/search/pipeline/steps/EsRecallUtils.java:54`、`:74`、`:78`。 |
| ES 前置过滤支持超管旁路。 | `EsRecallUtils.java:61`、`:62`。 |
| `kb_doc_search` 有专用顶层权限过滤。 | `EsRecallUtils.java:134`、`:152`、`:173`。 |
| 相似推荐也有 ACL filter，但索引范围仍是固定配置。 | `java_service/src/main/java/com/boyang/search/service/SimilarityService.java:732`、`:734`、`:735`；固定索引见 `:72`、`:747`、`:804`。 |
| 后置强校验使用 MySQL 权威数据。 | `java_service/src/main/java/com/boyang/search/security/PermissionGuard.java` 中 `canAccess(...)` 读取 `KbDocRegistry` 并执行可见度/ACL 判断。 |

### 11.3 入库路由与 token

| 结论 | 证据 |
| --- | --- |
| 前端/接口允许传 `targetIndex`、`searchQueries`、`visibility`、`deptCode`。 | `DocImportController.java:115`、`:122`、`:198`、`:214`、`:221`。 |
| 入库会把默认 `kb_document_v1` 视为未指定，然后按 tag 路由。 | `AbstractIngestStrategy.java:176` 到 `:181`。 |
| `searchQueries` 会进入异步 payload。 | `AbstractIngestStrategy.java:189`；`DocIngestService.java:365`。 |
| 入库会计算 ACL token 并传给 Python。 | `DocIngestService.java:388`、`:390`、`:485`。 |
| DEPT/GRANT 权限 token 计算入口已存在。 | `DocIngestService.java:485` 到 `:549`。 |

### 11.4 字段使用证据

| 字段 | 证据 |
| --- | --- |
| `metadata.search_queries` | `ai_service/core/rag_pipeline.py:1391` 写入；`KeywordRecallStrategy.java:379`、`:493`、`:560` 使用；`KeywordDocumentMatchStep.java:137`、`:146` 使用；`KeywordCoarseEvidenceStep.java:969` 使用。 |
| `metadata.title` / `doc_title` | `rag_pipeline.py:1135` 到 `:1138` 生成；`HybridRecallStrategy.java:229`、`KeywordRecallStrategy.java:547`、`:555`、`KeywordResultAssembleStep.java:119` 到 `:124` 使用。 |
| `tags_kw` | `rag_pipeline.py:1216` 到 `:1219` 生成；`HybridRecallStrategy.java:230`、`KeywordRecallStrategy.java:378`、`:492`、`:559` 使用。 |
| `publish_time` | `rag_pipeline.py:1374` 写入；`KeywordRecallStrategy.java:335`、`:382`、`:496` 读取；`KeywordResultAssembleStep.java:91` 到 `:93` 返回；`RerankStep.java:820` 使用。 |
| `owner_dept_id` | `doc_indexer.py:153`、`:247` 写入；`EsRecallUtils.java:173`、`KeywordResultAssembleStep.java:111` 到 `:113` 使用。 |
| `security_level` | 仅命中 `sys_tenant_policy` schema、entity、样例 mapping：`SysTenantPolicy.java:15`、`:33`；未命中实际查询 DSL。 |

### 11.5 风险证据

| 风险 | 证据 |
| --- | --- |
| `SimilarityService` 固定扫 `kb_document`。 | `application.yml:173` 默认 `EDITOR_SIMILARITY_CHUNK_FALLBACK_INDEX:kb_document`；`SimilarityService.java:72` 注入；`:747`、`:804` 查询。 |
| 旧 `SearchController` analyze 固定 `kb_document_v1`。 | `SearchController.java:586`。 |
| `SearchController` 分片查询存在空指针条件。 | `SearchController.java:395` 同时判断 `fileName == null && fileName.trim().isEmpty()`。 |
| `colloquial_vector` 主链路废弃但历史脚本仍可写。 | `ai_service/core/rag_pipeline.py:1443`、`:1444` 标明废弃；`scripts/batch_colloquial_generator.py:2`、`:118` 仍写；`ai_service/scripts/rag_pipeline.py:973` 到 `:1022` 仍有后台回填逻辑。 |
| 数据库配置存在 profile 差异。 | `application.yml:12` 默认 MySQL；`application-dev.yml:13`、`:18` 与 `application-prod.yml:10` 使用 PostgreSQL。 |

### 11.6 当前 ES 环境证据

| 项 | 结果 |
| --- | --- |
| `kb_document` 读别名 | 指向 `kb_document_public/official/law/notice/news/v1`。 |
| 当前问答索引 | `kb_qa_pairs`，读别名 `kb_qa_read`；未发现 `kb_doc_qa`。 |
| `kb_document_public` 字段填充 | `metadata.acl_tokens=22/22`、`metadata.owner_dept_id=22/22`、`vector=22/22`。 |
| `kb_document_official` 字段填充 | `metadata.acl_tokens=669/669`、顶层 `acl_tokens=96/669`、`metadata.owner_dept_id=669/669`、`vector=669/669`。 |
| `kb_doc_search_v1` 字段填充 | `acl_tokens=21/21`、`owner_dept_id=21/21`、`doc_title=1/21`、`security_level=1/21`。 |
| `kb_doc_meta_v2` 字段填充 | `acl_tokens=5/5`、`owner_dept_id=5/5`、`doc_vector=5/5`。 |
| `kb_qa_pairs` 字段填充 | `acl_tokens=419/419`、`question_vector=419/419`；`owner_dept_id/security_level/visible_depts/vector` 当前为 0。 |
