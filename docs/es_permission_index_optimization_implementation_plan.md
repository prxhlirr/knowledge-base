# ES 索引权限与字段优化实施计划

## 1. 背景与目标

当前知识库检索索引已经拆分为多个物理索引：

- `kb_document_official`
- `kb_document_public`
- `kb_document_law`
- `kb_document_news`
- `kb_document_notice`
- `kb_document_v1`
- `kb_doc_search_v1`
- `kb_doc_meta_v2`
- `kb_qa_pairs`

现有索引已经预留了一些权限字段，例如 `acl_tokens`、`owner_dept_id`、`visible_depts`，但真实数据中单位权限字段没有形成闭环。部分字段存在类型不一致、路径不一致、含义重复、mapping 中存在但数据为空等问题。

本次优化目标：

1. 支持角色按物理索引和文档类型授权。
2. 支持单位权限：文档挂在 A 单位时，A 单位及上级单位可见。
3. 保证现有检索、相似文档、QA、文档详情、权限过滤功能不受影响。
4. 通过新索引和 alias 灰度迁移，逐步清理冗余字段。
5. 为后续不同索引分布到不同 ES 节点或集群预留能力。

## 2. 核心原则

本次改造不采用直接删除旧字段的方式。

必须遵守以下原则：

1. 先新增标准字段，再切查询逻辑。
2. 新旧字段双写一段时间。
3. 查询层新字段优先，旧字段兜底。
4. 历史数据离线补齐，不重新解析、不重新切片、不重新向量化。
5. alias 灰度切换，保留旧索引回滚窗口。
6. 权限最终以数据库为权威，ES 只做召回阶段的高性能权限投影。

## 3. 目标字段规范

### 3.1 通用权限字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `source_index` | `keyword` | 文档实际写入的物理索引，例如 `kb_document_official` |
| `index_code` | `keyword` | 业务索引编码，例如 `official/public/law/news/notice` |
| `doc_type` | `keyword` | 文档类型，例如 `通知/通告/法规/公示` |
| `visibility` | `keyword` | 可见性，例如 `PUBLIC/INTERNAL/DEPT/PRIVATE/GRANT` |
| `acl_tokens` | `keyword` | ES 召回阶段 ACL 投影 |
| `owner_unit_code` | `keyword` | 文档归属单位编码 |
| `visible_unit_codes` | `keyword` | 可见单位编码数组，包含归属单位和上级单位 |
| `permission_version` | `long` | 权限投影版本 |
| `security_level` | `integer` | 密级或安全等级，默认 `0` |

### 3.2 `kb_document_*` chunk 索引字段

建议保留：

| 字段 | 类型 | 说明 |
|---|---|---|
| `doc_id` | `keyword` | 文档唯一 ID |
| `doc_version` | `integer` | 文档版本 |
| `source` | `keyword` | 来源文件名 |
| `source_name` | `keyword` | 来源文件名冗余字段 |
| `title` | `text + keyword` | 标题检索和精确匹配 |
| `content` | `text` | 全文 BM25 检索 |
| `display_content` | `text, index=false` | 展示片段 |
| `content_hash` | `keyword` | 内容哈希 |
| `chunk_id` | `integer` | chunk 序号 |
| `chunk_granularity` | `keyword` | `coarse/fine` |
| `parent_chunk_id` | `keyword` | 父 chunk ID |
| `vector` | `dense_vector` | chunk 语义向量 |
| `sparse_vector` | `rank_features` | 稀疏向量召回 |
| `keywords` | `keyword` | chunk 关键词 |
| `tags` | `keyword` | 文档标签 |
| `document_number` | `keyword + text/ngram` | 文号 |
| `publish_time` | `date` | 发文时间，必须保留 |
| `is_latest` | `boolean` | 最新版本 |
| `section_path` | `text + keyword` | 章节路径 |
| `quality_score` | `float` | chunk 质量分 |

过渡期兼容保留：

| 字段 | 原因 |
|---|---|
| `metadata.source` | 现有详情、回查、取证逻辑依赖 |
| `metadata.title` | 现有标题检索和展示依赖 |
| `metadata.doc_type` | 现有过滤使用 `metadata.doc_type.keyword` |
| `metadata.document_number` | 现有文号检索依赖 |
| `metadata.acl_tokens` | 现有权限过滤依赖 |
| `metadata.owner_dept_id` | 旧单位权限兜底 |
| `metadata.is_latest` | 现有召回过滤依赖 |
| `metadata.doc_id` | 文档详情和 chunk 回查依赖 |

下一版可清理：

| 字段 | 处理 |
|---|---|
| `colloquial_vector` | 移除 |
| `metadata.visible_depts` | 替换为 `visible_unit_codes` |
| `metadata.dept_l2/l4/l6/l9/dept_code_full` | 替换为 `owner_unit_code/visible_unit_codes` |
| `metadata.access_groups` | 由 `acl_tokens` 替代 |
| `metadata.search_queries` | 迁移到 `kb_doc_search_v2.search_text/search_aliases` |
| `doc_title` | 合并到 `title` |

### 3.3 `kb_doc_search_v2` 字段

`kb_doc_search_v2` 是文档级关键词预检索索引，必须支持按物理索引和文档类型过滤。

| 字段 | 类型 | 说明 |
|---|---|---|
| `doc_id` | `keyword` | 文档 ID |
| `doc_version` | `integer` | 文档版本 |
| `source` | `keyword` | 来源文件名 |
| `source_name` | `keyword` | 来源文件名 |
| `title` | `text + keyword + ngram` | 标题 |
| `document_number` | `keyword + text/ngram` | 文号 |
| `doc_terms` | `text` | 文档级聚合检索正文 |
| `summary` | `text` | 文档摘要 |
| `keywords` | `keyword` | 关键词 |
| `tags` | `keyword` | 标签 |
| `entities` | `keyword` | 实体 |
| `section_titles` | `text + keyword` | 章节标题 |
| `search_text` | `text` | 人工检索增强文本 |
| `search_aliases` | `keyword` | 人工检索增强词 |
| `source_index` | `keyword` | 物理索引 |
| `index_code` | `keyword` | 业务索引编码 |
| `doc_type` | `keyword` | 文档类型 |
| `publish_time` | `date` | 发文时间 |
| `is_latest` | `boolean` | 最新版本 |
| `visibility` | `keyword` | 可见性 |
| `acl_tokens` | `keyword` | ACL 投影 |
| `owner_unit_code` | `keyword` | 归属单位 |
| `visible_unit_codes` | `keyword` | 可见单位 |
| `permission_version` | `long` | 权限版本 |
| `security_level` | `integer` | 安全等级 |
| `updated_at` | `date` | 更新时间 |

### 3.4 `kb_doc_meta_v3` 字段

`kb_doc_meta_v3` 用于文档级向量和相似文档。

| 字段 | 类型 | 说明 |
|---|---|---|
| `doc_id` | `keyword` | 文档 ID |
| `doc_version` | `integer` | 文档版本 |
| `source` | `keyword` | 来源文件名 |
| `source_name` | `keyword` | 来源文件名 |
| `title` | `text + keyword` | 标题 |
| `summary` | `text, index=false` | 摘要 |
| `content_hash` | `keyword` | 内容哈希 |
| `doc_vector` | `dense_vector` | 文档级向量 |
| `source_index` | `keyword` | 物理索引 |
| `index_code` | `keyword` | 业务索引编码 |
| `doc_type` | `keyword` | 文档类型 |
| `data_source` | `keyword` | 数据来源 |
| `chunk_count` | `integer` | chunk 数 |
| `publish_time` | `date` | 发文时间 |
| `is_latest` | `boolean` | 最新版本 |
| `visibility` | `keyword` | 可见性 |
| `acl_tokens` | `keyword` | ACL 投影 |
| `owner_unit_code` | `keyword` | 归属单位 |
| `visible_unit_codes` | `keyword` | 可见单位 |
| `permission_version` | `long` | 权限版本 |
| `security_level` | `integer` | 安全等级 |
| `updated_at` | `date` | 更新时间 |

### 3.5 `kb_qa_pairs_v2` 字段

QA 索引只要参与召回，就必须纳入同一套权限投影。

| 字段 | 类型 | 说明 |
|---|---|---|
| `qa_id` | `keyword` | QA 唯一 ID |
| `doc_id` | `keyword` | 文档 ID |
| `doc_hash` | `keyword` | 文档 hash，兼容旧数据 |
| `doc_version` | `integer` | 文档版本 |
| `source` | `keyword` | 来源文件名 |
| `title` | `text + keyword` | 文档标题 |
| `question` | `text` | 问题 |
| `answer_content` | `text` | 答案内容 |
| `answer_chunk_id` | `keyword` | 答案来源 chunk |
| `section_path` | `keyword` | 章节路径 |
| `question_vector` | `dense_vector` | 问题向量 |
| `source_index` | `keyword` | 物理索引 |
| `index_code` | `keyword` | 业务索引编码 |
| `doc_type` | `keyword` | 文档类型 |
| `data_source` | `keyword` | 数据来源 |
| `publish_time` | `date` | 发文时间 |
| `is_latest` | `boolean` | 最新版本 |
| `visibility` | `keyword` | 可见性 |
| `acl_tokens` | `keyword` | ACL 投影 |
| `owner_unit_code` | `keyword` | 归属单位 |
| `visible_unit_codes` | `keyword` | 可见单位 |
| `permission_version` | `long` | 权限版本 |
| `security_level` | `integer` | 安全等级 |

## 4. 对现有功能的影响与优化策略

### 4.1 直接替换索引的风险

如果直接删除旧字段，只保留新字段，会影响：

1. 全文检索。
2. 关键词检索。
3. 语义检索。
4. 文档级预检索。
5. 相似文档。
6. QA 召回。
7. 权限过滤。
8. 文档详情和版本回查。
9. 搜索结果展示。
10. 敏感词过滤。

原因是现有代码大量依赖：

```text
metadata.source
metadata.title
metadata.document_number
metadata.doc_type.keyword
metadata.acl_tokens
metadata.owner_dept_id
metadata.is_latest
metadata.doc_id
doc_title
```

### 4.2 兼容策略

短期新索引必须双字段兼容：

```text
新字段优先:
source
title
document_number
doc_type
acl_tokens
is_latest
owner_unit_code
visible_unit_codes

旧字段兜底:
metadata.source
metadata.title
metadata.document_number
metadata.doc_type
metadata.acl_tokens
metadata.is_latest
metadata.owner_dept_id
```

查询层必须支持双读：

```text
doc_type: doc_type -> metadata.doc_type.keyword
source: source -> metadata.source
title: title -> metadata.title -> doc_title
acl_tokens: acl_tokens -> metadata.acl_tokens
unit permission: visible_unit_codes -> owner_dept_id
```

## 5. 需要改动的代码模块

### 5.1 入库链路

涉及模块：

- `java_service/src/main/java/com/boyang/search/strategy/ingest/AbstractIngestStrategy.java`
- `java_service/src/main/java/com/boyang/search/service/DocIngestService.java`
- `java_service/src/main/java/com/boyang/search/job/OutboxPoller.java`
- `ai_service/core/rag_pipeline.py`
- `ai_service/core/indexing/doc_indexer.py`
- `ai_service/task_worker.py`
- `ai_service/task_worker_qa.py`

改动点：

1. 明确 `requested_target_index` 和 `resolved_target_index`。
2. 入库时计算并写入 `source_index`、`index_code`。
3. 入库时写入 `owner_unit_code`、`visible_unit_codes`、`permission_version`。
4. `publish_time` 必须保留并统一格式。
5. `searchQueries` 不再重复写入每个 chunk 的 `metadata.search_queries`，改写入 `kb_doc_search_v2.search_text/search_aliases`。
6. `doc_indexer.update_doc_search()` 写入 `source_index/index_code/visible_unit_codes`。
7. `doc_indexer.update_doc_meta()` 写入 `source_index/index_code/visible_unit_codes`。
8. QA 写入链路写入 `source_index/index_code/doc_type/visible_unit_codes`。

### 5.2 查询入口与索引解析

涉及模块：

- `SearchServiceV2.java`
- `SearchIndexResolver.java`
- `IndexAliasResolver.java`
- `IndexAclGuard.java`

改动点：

1. 角色权限解析返回物理索引列表，不再默认返回 `kb_document`。
2. 普通用户查询直接使用允许访问的物理索引，例如 `kb_document_official,kb_document_public`。
3. 管理员允许访问全部物理索引。
4. 如果出现无可读索引，返回 `__no_readable_index__`。
5. 保留 `kb_document` alias 作为兼容路径和应急回滚路径。

### 5.3 召回链路

涉及模块：

- `KeywordRecallStrategy.java`
- `SemanticRecallStrategy.java`
- `HybridRecallStrategy.java`
- `LiteralRecallStep.java`
- `DocExpansionStep.java`
- `KeywordCoarseEvidenceStep.java`
- `RerankStep.java`
- `KeywordResultAssembleStep.java`
- `SimilarityService.java`

改动点：

1. 所有 chunk 查询使用 `context.getResolvedIndexPattern()`。
2. 禁止把多物理索引重新改回 `kb_document`。
3. 查询条件新字段优先，旧字段兜底。
4. `doc_type` 过滤支持 `doc_type` 和 `metadata.doc_type.keyword`。
5. 单位权限过滤支持 `visible_unit_codes` 和旧 `owner_dept_id`。
6. `kb_doc_search_v2` 查询增加 `source_index/index_code` 过滤。
7. `kb_qa_pairs_v2` 查询增加 `source_index/index_code/doc_type/visible_unit_codes` 过滤。
8. 结果组装优先读顶层 `title/source/tags/document_number`，再读旧 `metadata.*`。

### 5.4 权限服务

涉及模块：

- `PermissionGuard.java`
- `DocPermissionService.java`
- `DocAclProjectionService.java`
- `KbDocAclSubjectService.java`
- `IndexAclSubjectService.java`

改动点：

1. DB 权限仍为权威。
2. ES 中的 `acl_tokens`、`visible_unit_codes` 只是召回投影。
3. 授权变更时同步更新：
   - `kb_document_*`
   - `kb_doc_search_v2`
   - `kb_doc_meta_v3`
   - `kb_qa_pairs_v2`
4. 投影失败进入重试任务。
5. 搜索结果最终仍由 DB `PermissionGuard` 批量兜底。

## 6. 数据库配置改造

### 6.1 索引注册表

建议统一维护：

```text
sys_index_routing
- tag_code
- tag_name
- index_code
- target_index
- is_active
- description
```

如果当前表没有 `index_code`，需要新增。

### 6.2 角色索引权限表

新增：

```text
kb_role_index_policy
- id
- role_code
- index_code
- es_index
- permission_type
- enabled
- created_at
- updated_at
```

### 6.3 角色文档类型权限表

新增：

```text
kb_role_doc_type_policy
- id
- role_code
- index_code
- doc_type
- enabled
- created_at
- updated_at
```

### 6.4 权限版本表

新增或复用：

```text
kb_permission_projection_version
- id
- version
- reason
- created_at
```

用途：

1. 标识 ES 权限投影版本。
2. 支持增量补偿。
3. 支持按版本审计。

## 7. 历史数据迁移方案

### 7.1 数据来源

权威来源优先级：

1. `kb_doc_registry`
2. `sys_index_routing`
3. 组织树
4. ES 旧字段

从 `kb_doc_registry` 读取：

```text
doc_id
source_name
target_index
dept_code
unit
visibility
doc_number
tags
publish_time
status
is_latest
```

### 7.2 字段转换规则

| 目标字段 | 来源 |
|---|---|
| `source_index` | `kb_doc_registry.target_index` |
| `index_code` | `sys_index_routing.target_index -> index_code` |
| `owner_unit_code` | `kb_doc_registry.dept_code` |
| `visible_unit_codes` | `dept_code + 所有上级单位编码` |
| `publish_time` | `kb_doc_registry.publish_time` |
| `document_number` | `kb_doc_registry.doc_number` 或 `metadata.dynamic_meta.docNumber` |
| `tags` | `kb_doc_registry.tags` 或旧 `metadata.tags` |
| `doc_type` | 旧 `metadata.doc_type.keyword` 或路由表推导 |
| `acl_tokens` | 旧 `metadata.acl_tokens` 或 DB 授权投影 |
| `security_level` | DB 安全等级，缺省 `0` |

### 7.3 迁移方式

推荐使用 scroll + bulk，而不是简单 `_reindex`。

原因：

1. 需要从 DB 补字段。
2. 需要字段重命名。
3. 需要把字符串标签拆成数组。
4. 需要把 `dynamic_meta.docNumber` 抽取到标准字段。
5. 需要生成单位权限字段。

### 7.4 迁移对象

1. `kb_document_official/public/law/news/notice/v1` -> 新物理索引。
2. `kb_doc_search_v1` -> `kb_doc_search_v2`。
3. `kb_doc_meta_v2` -> `kb_doc_meta_v3`。
4. `kb_qa_pairs` -> `kb_qa_pairs_v2`。

### 7.5 不需要重新生成的数据

以下数据不需要重新计算：

1. chunk 切片。
2. dense vector。
3. sparse vector。
4. doc_vector。
5. question_vector。
6. QA 内容。

只做字段补齐和结构归一。

## 8. 分阶段实施计划

### 阶段 0：基线盘点

目标：冻结现状，建立可回归基线。

任务：

1. 导出现有 mapping。
2. 导出现有 alias。
3. 统计各索引文档数。
4. 统计关键字段存在率。
5. 抽样保存典型搜索请求和响应。
6. 建立权限测试用户：
   - 管理员
   - 普通角色
   - 单位上级用户
   - 单位本级用户
   - 无权限用户
7. 建立检索基线：
   - 关键词检索
   - 语义检索
   - 混合检索
   - 相似文档
   - QA 召回
   - 文档详情
   - 文档版本

验收：

1. 有完整索引基线报告。
2. 有搜索结果基线样例。
3. 有权限基线样例。

### 阶段 1：新 mapping 和 template

目标：创建新索引契约，但不切流量。

任务：

1. 新增 `kb_document_template_v2`。
2. 新建 `kb_doc_search_v2`。
3. 新建 `kb_doc_meta_v3`。
4. 新建 `kb_qa_pairs_v2`。
5. 配置写别名候选，但暂不切换。
6. 验证 mapping 字段类型。

验收：

1. 新索引创建成功。
2. `doc_type/acl_tokens/visible_unit_codes/source_index/index_code` 均为 `keyword`。
3. `publish_time` 为 `date`。
4. `display_content` 不建 keyword 子字段。
5. `colloquial_vector` 不进入新 template。

### 阶段 2：新入库双写字段

目标：新入库文档在旧字段可用的同时，写入新标准字段。

任务：

1. Java 入库链路补 `resolved_target_index`。
2. Python 入库链路写入 `source_index/index_code`。
3. 写入 `owner_unit_code/visible_unit_codes/permission_version`。
4. 写入标准顶层字段：
   - `source`
   - `title`
   - `doc_type`
   - `document_number`
   - `publish_time`
   - `tags`
   - `acl_tokens`
   - `is_latest`
5. 同步保留旧 `metadata.*` 字段。
6. `doc_search` 写入 `search_text/search_aliases`。
7. QA 写入标准权限字段。

验收：

1. 新上传文档可以在旧查询链路检索到。
2. 新上传文档标准字段存在率为 100%。
3. 新上传文档权限字段存在率为 100%。

### 阶段 3：查询层双读兼容

目标：查询逻辑开始优先使用新字段，旧字段兜底。

任务：

1. 封装统一字段解析器，例如 `SearchFieldResolver`。
2. 封装权限过滤器，例如 `SearchPermissionFilterBuilder`。
3. 改造 `SearchIndexResolver` 返回物理索引列表。
4. 改造 `KeywordRecallStrategy`。
5. 改造 `SemanticRecallStrategy`。
6. 改造 `HybridRecallStrategy`。
7. 改造 `LiteralRecallStep`。
8. 改造 `DocExpansionStep`。
9. 改造 `KeywordCoarseEvidenceStep`，移除多索引回退 `kb_document` 的逻辑。
10. 改造 `KeywordResultAssembleStep`。
11. 改造 `RerankStep`。
12. 改造 `SimilarityService`。
13. 改造 QA 召回链路。

验收：

1. 新旧索引均可检索。
2. 关键词、语义、混合检索结果不低于基线。
3. 文档详情、版本、相似文档、QA 功能正常。
4. 普通用户不能查到未授权物理索引中的文档。
5. 管理员可查全部。

### 阶段 4：历史数据迁移和回填

目标：把历史数据迁移到新索引，并补齐权限字段。

任务：

1. 编写迁移脚本。
2. 支持 dry-run。
3. 支持断点续跑。
4. 支持批量大小配置。
5. 支持失败重试。
6. 迁移 chunk 索引。
7. 迁移 `kb_doc_search_v1`。
8. 迁移 `kb_doc_meta_v2`。
9. 迁移 `kb_qa_pairs`。
10. 输出迁移报告。

验收：

1. 文档数一致。
2. `source_index/index_code` 补齐率 100%。
3. `visible_unit_codes` 补齐率符合 DB 可补齐范围。
4. `publish_time` 保留。
5. `document_number/tags/title` 迁移正确。
6. 向量字段维度正确。
7. 搜索基线回归通过。

### 阶段 5：灰度切换 alias

目标：逐步把读写流量切到新索引。

任务：

1. 切 `kb_doc_search` 到 `kb_doc_search_v2`。
2. 切 `kb_doc_search_write` 到 `kb_doc_search_v2`。
3. 切 `kb_doc_meta_read` 到 `kb_doc_meta_v3`。
4. 切 `kb_doc_meta_write` 到 `kb_doc_meta_v3`。
5. 切 `kb_qa_read` 到 `kb_qa_pairs_v2`。
6. 切 `kb_qa_write` 到 `kb_qa_pairs_v2`。
7. 逐步切 chunk 写别名。
8. 保留旧索引只读。

验收：

1. 切换后搜索成功率正常。
2. P95/P99 延迟不劣化。
3. 权限审计无越权。
4. 可一键回滚 alias。

### 阶段 6：旧字段下线

目标：确认无旧字段访问后，清理下一版 mapping。

任务：

1. 日志统计旧字段 fallback 命中率。
2. 当 fallback 命中率连续一段时间为 0 后，准备下线。
3. 新 template 移除：
   - `colloquial_vector`
   - `doc_title`
   - `visible_depts`
   - `metadata.search_queries`
   - `metadata.access_groups`
   - `metadata.dept_l2/l4/l6/l9/dept_code_full`
4. 删除旧索引或归档。

验收：

1. 无代码访问旧字段。
2. 无旧字段 fallback 命中。
3. 旧索引可删除或归档。

## 9. 任务拆解

### 后端 Java

1. 新增角色索引权限表实体、mapper、service。
2. 新增角色文档类型权限表实体、mapper、service。
3. 改造 `SearchIndexResolver`。
4. 改造 `IndexAclGuard`。
5. 新增统一查询字段解析器。
6. 新增统一权限过滤构造器。
7. 改造所有召回 Step。
8. 改造结果组装逻辑。
9. 改造权限投影服务。
10. 增加权限审计日志。
11. 增加回归测试。

### AI/Python

1. 改造 `rag_pipeline.py` 入库字段。
2. 改造 `doc_indexer.py` 写入 `kb_doc_search_v2/kb_doc_meta_v3`。
3. 改造 QA 写入字段。
4. 新增历史迁移脚本。
5. 新增字段存在率校验脚本。
6. 新增迁移报告输出。

### ES 运维

1. 创建新 index template。
2. 创建新索引。
3. 配置 alias。
4. 执行迁移。
5. 监控迁移性能。
6. 灰度切读写 alias。
7. 保留旧索引回滚窗口。

### 前端

1. 角色管理增加索引权限配置。
2. 角色管理增加文档类型权限配置。
3. 文档导入继续保留 `publishTime`。
4. 文档导入 `searchQueries` 改为检索增强词说明。
5. 权限配置增加可见范围说明。

## 10. 测试计划

### 10.1 功能测试

1. 上传文档到不同业务索引。
2. 按角色查询不同物理索引。
3. 按文档类型查询。
4. 按单位权限查询。
5. 管理员查询全部。
6. 关键词检索。
7. 语义检索。
8. 混合检索。
9. 文档详情。
10. 文档版本。
11. 相似文档。
12. QA 召回。

### 10.2 权限测试

测试矩阵：

| 用户 | 角色 | 单位 | 预期 |
|---|---|---|---|
| 管理员 | admin | 任意 | 可看全部 |
| 普通用户 A | official | A | 只能看 official 且单位可见 |
| 普通用户 B | public | B | 只能看 public 且单位可见 |
| 上级单位用户 | official | A 上级 | 可看 A 单位文档 |
| 无权限用户 | none | 任意 | 查不到受限文档 |

### 10.3 性能测试

关注指标：

1. ES 查询 P50/P95/P99。
2. 每次查询命中的 shard 数。
3. `kb_doc_search_v2` 查询耗时。
4. chunk 索引查询耗时。
5. 权限过滤后结果数量。
6. DB `PermissionGuard` 兜底耗时。

### 10.4 回归测试

必须对比迁移前后：

1. TopK 文档是否一致。
2. 文档标题是否一致。
3. 摘要是否一致。
4. 证据 chunk 是否一致。
5. 权限结果是否一致。
6. QA 引用是否一致。

## 11. 回滚方案

alias 切换必须保留回滚命令。

回滚策略：

1. 新索引只读异常时，读 alias 切回旧索引。
2. 新写入异常时，写 alias 切回旧索引。
3. 查询层保留旧字段 fallback。
4. 旧索引至少保留一个完整业务周期。

回滚条件：

1. 搜索成功率明显下降。
2. P95/P99 延迟明显劣化。
3. 出现权限越权。
4. 文档详情大量为空。
5. QA 召回异常。

## 12. 风险与控制

| 风险 | 控制 |
|---|---|
| 字段路径变化导致召回下降 | 双字段查询，新字段优先，旧字段兜底 |
| 权限字段补错导致越权 | DB `PermissionGuard` 后置强校验 |
| `kb_doc_search` 绕过索引权限 | `kb_doc_search_v2` 必须补 `source_index/index_code` |
| QA 召回越权 | `kb_qa_pairs_v2` 纳入同一权限投影 |
| 历史数据单位缺失 | 缺失数据标记为待治理，不默认扩大权限 |
| alias 切换不可回滚 | 所有 alias 操作保留反向脚本 |
| 迁移耗时过长 | scroll + bulk，支持断点续跑 |

## 13. 推荐里程碑

| 里程碑 | 内容 | 产出 |
|---|---|---|
| M1 | 基线盘点 | mapping、alias、字段存在率、搜索基线 |
| M2 | 新索引创建 | template、新索引、字段校验 |
| M3 | 新入库双写 | 新上传文档字段完整 |
| M4 | 查询双读 | 新旧索引均可检索 |
| M5 | 历史迁移 | 迁移报告、字段补齐报告 |
| M6 | 灰度切换 | alias 切换、监控通过 |
| M7 | 清理旧字段 | 旧字段访问为 0，旧索引归档 |

## 14. 最终落地顺序

推荐顺序：

1. 不删除任何旧字段。
2. 先补新字段。
3. 新入库双写。
4. 查询层双读。
5. 历史数据迁移。
6. alias 灰度切换。
7. 观察 fallback 命中率。
8. 最后一版索引再清理旧字段。

这样可以最大限度保证现有功能不受影响，同时完成权限控制、字段治理和后续扩展能力建设。
