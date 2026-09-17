# ES 权限控制与索引优化实施方案（整合落地版 / PostgreSQL 手动执行）

生成时间：2026-06-25

## 1. 本版约束

本方案基于三条新约束重排：

1. P0 级数据库修复不走自动 migration，由 DBA/运维手动在数据库执行。
2. 所有数据库脚本均按 PostgreSQL 语法编写。
3. 整合 `es_permission_index_optimization_implementation_plan.md` 中的字段规范、迁移、灰度、回滚、测试矩阵，同时保留代码验证版中的真实代码约束。

本文档不要求立即改代码，但每个任务都标出“已有入口”或“新增开发”。

## 2. 已验证现状

### 2.1 当前可复用能力

| 能力 | 当前状态 | 证据 |
| --- | --- | --- |
| 索引级 ACL | 代码已存在，表脚本在 `db/offline` | `IndexAclSubjectService`、`IndexAclGuard`、`IndexAclSubjectsController`、`db/offline/004_index_acl_subjects.sql` |
| 文档级 ACL | 代码已存在 | `kb_doc_acl_subjects`、`DocAclProjectionService`、`PermissionGuard` |
| ES 前置权限过滤 | 已存在 | `EsRecallUtils` 同时查顶层 `acl_tokens` 和 `metadata.acl_tokens` |
| 数据库后置强校验 | 已存在 | `PermissionGuard` 读取 `kb_doc_registry` |
| 读索引解析 | 已存在 | `SearchServiceV2` 调 `SearchIndexResolver.resolve()` |
| 入库目标索引路由 | 已存在 | `AbstractIngestStrategy` 按 `targetIndex/tag` 路由，Python 端再次解析 |
| 管理员全量查看 | 已存在 | `_SUPER_ADMIN` 与 `identity.isSuperAdmin()` 双旁路 |

### 2.2 当前缺口

| 缺口 | 影响 | 处理方式 |
| --- | --- | --- |
| `kb_index_acl_subjects` 需要手动建表 | 没表时索引级 ACL 代码不可用 | P0 手动 PostgreSQL 脚本 |
| 文档类型级 ACL 当前没有独立表 | “某角色看某些文档类型”若不拆物理索引，需要新增能力 | P1 新增 `kb_doc_type_acl_subjects` |
| `SimilarityService` 固定读 `kb_document` | 角色索引权限可能被相似推荐绕开 | P0/P1 改为使用同一索引解析结果 |
| `kb_doc_search/kb_doc_meta/kb_qa` 缺 `source_index/index_code` | 文档级预筛、相似、QA 无法按物理索引收敛 | P1/P2 补字段并迁移 |
| `security_level` 未接入查询 DSL | 不能当作已生效权限 | 暂保留字段，不作为第一阶段权限控制 |
| `colloquial_vector` 主链路废弃但脚本仍可写 | 新索引不应继续承载冗余向量 | P1 脚本归档/禁用 |

## 3. 最终权限模型

### 3.1 三层权限

1. 租户级：`sys_tenant_policy.allowed_indices` 限定 appCode 最大可读范围。
2. 索引级：`kb_index_acl_subjects` 限定用户/角色/部门可读哪些物理索引。
3. 文档级：`acl_tokens` 作为 ES 高性能投影，`PermissionGuard` 作为 DB 权威兜底。

### 3.2 单位权限

落地原则：

1. DB 权威字段仍来自 `kb_doc_registry.dept_code` / 当前文档归属部门字段。
2. ES 权限投影优先使用 `acl_tokens`，写入 `dept::{部门编码}` token。
3. 文档挂在 A 部门时，ES 写 A 部门及其上级部门 token，满足“A 及上级可见”。
4. `owner_dept_id` 作为当前代码兼容字段继续保留。
5. `owner_unit_code/visible_unit_codes` 作为 v2 标准字段引入，用于后续分析、审计和显式单位过滤。

### 3.3 角色看索引/文档类型

| 需求 | 第一阶段实现 | 第二阶段增强 |
| --- | --- | --- |
| 角色看某些索引 | 使用已有 `kb_index_acl_subjects` | 增加前端配置页面 |
| 角色看某些文档大类 | 如果文档大类已拆到物理索引，仍使用 `kb_index_acl_subjects` | 无需额外表 |
| 角色看同一索引内某些 `doc_type` | 新增 `kb_doc_type_acl_subjects` + 查询 DSL doc_type filter | 增加前端配置页面 |
| 管理员看全部 | 使用 `_SUPER_ADMIN` / `isSuperAdmin` | 保持现有逻辑 |

## 4. P0 手动 PostgreSQL 脚本

以下脚本由 DBA/运维手动执行。执行前先在测试库验证。

### 4.1 索引级 ACL 表

当前代码已直接使用 `kb_index_acl_subjects`，所以这是 P0 必需脚本。

```sql
BEGIN;

CREATE TABLE IF NOT EXISTS public.kb_index_acl_subjects (
    id BIGSERIAL PRIMARY KEY,
    index_name VARCHAR(255) NOT NULL,
    read_alias VARCHAR(255),
    subject_type VARCHAR(32) NOT NULL,
    subject_value VARCHAR(255) NOT NULL,
    scope VARCHAR(32) NOT NULL DEFAULT 'READ',
    effect VARCHAR(16) NOT NULL DEFAULT 'ALLOW',
    expires_at TIMESTAMP WITHOUT TIME ZONE,
    is_active INT NOT NULL DEFAULT 1,
    created_by VARCHAR(128),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_kias_index_scope
    ON public.kb_index_acl_subjects (index_name, scope, is_active);

CREATE INDEX IF NOT EXISTS idx_kias_subject
    ON public.kb_index_acl_subjects (subject_type, subject_value, is_active);

CREATE INDEX IF NOT EXISTS idx_kias_effect_expire
    ON public.kb_index_acl_subjects (effect, expires_at, is_active);

COMMENT ON TABLE public.kb_index_acl_subjects IS '索引级 ACL：控制用户/角色/部门是否可读取某个 kb_document_* 物理索引';
COMMENT ON COLUMN public.kb_index_acl_subjects.index_name IS '物理索引名，例如 kb_document_public';
COMMENT ON COLUMN public.kb_index_acl_subjects.subject_type IS '主体类型：USER/ROLE/DEPT/ALL/AUTHENTICATED';
COMMENT ON COLUMN public.kb_index_acl_subjects.subject_value IS '主体值；ALL/AUTHENTICATED 可使用 *';
COMMENT ON COLUMN public.kb_index_acl_subjects.scope IS '权限范围，当前代码使用 READ';
COMMENT ON COLUMN public.kb_index_acl_subjects.effect IS 'ALLOW 或 DENY，DENY 优先';

COMMIT;
```

### 4.2 文档类型级 ACL 表

当前代码尚未使用该表。只有当“同一个物理索引内还要按 doc_type 给角色授权”时才需要新增开发并执行此脚本。

```sql
BEGIN;

CREATE TABLE IF NOT EXISTS public.kb_doc_type_acl_subjects (
    id BIGSERIAL PRIMARY KEY,
    index_name VARCHAR(255),
    index_code VARCHAR(64),
    doc_type VARCHAR(128) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_value VARCHAR(255) NOT NULL,
    scope VARCHAR(32) NOT NULL DEFAULT 'READ',
    effect VARCHAR(16) NOT NULL DEFAULT 'ALLOW',
    expires_at TIMESTAMP WITHOUT TIME ZONE,
    is_active INT NOT NULL DEFAULT 1,
    created_by VARCHAR(128),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_kdtas_type_scope
    ON public.kb_doc_type_acl_subjects (doc_type, scope, is_active);

CREATE INDEX IF NOT EXISTS idx_kdtas_index_type
    ON public.kb_doc_type_acl_subjects (index_name, doc_type, is_active);

CREATE INDEX IF NOT EXISTS idx_kdtas_subject
    ON public.kb_doc_type_acl_subjects (subject_type, subject_value, is_active);

COMMENT ON TABLE public.kb_doc_type_acl_subjects IS '文档类型级 ACL：控制主体是否可读取某个 doc_type';
COMMENT ON COLUMN public.kb_doc_type_acl_subjects.index_name IS '可为空；为空表示该 doc_type 全局生效';
COMMENT ON COLUMN public.kb_doc_type_acl_subjects.index_code IS '业务索引编码，如 public/law/official';

COMMIT;
```

### 4.3 任务执行后校验 SQL

```sql
SELECT to_regclass('public.kb_index_acl_subjects') AS index_acl_table;
SELECT to_regclass('public.kb_doc_type_acl_subjects') AS doc_type_acl_table;

SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename IN ('kb_index_acl_subjects', 'kb_doc_type_acl_subjects')
ORDER BY tablename, indexname;
```

### 4.4 示例授权数据

```sql
-- role::official_reader 可读公文索引
INSERT INTO public.kb_index_acl_subjects
    (index_name, read_alias, subject_type, subject_value, scope, effect, created_by)
VALUES
    ('kb_document_official', 'kb_document', 'ROLE', 'official_reader', 'READ', 'ALLOW', 'manual_admin');

-- role::public_reader 可读公示索引
INSERT INTO public.kb_index_acl_subjects
    (index_name, read_alias, subject_type, subject_value, scope, effect, created_by)
VALUES
    ('kb_document_public', 'kb_document', 'ROLE', 'public_reader', 'READ', 'ALLOW', 'manual_admin');

-- 如果启用文档类型 ACL：role::notice_reader 可读“通知”
INSERT INTO public.kb_doc_type_acl_subjects
    (index_name, index_code, doc_type, subject_type, subject_value, scope, effect, created_by)
VALUES
    (NULL, NULL, '通知', 'ROLE', 'notice_reader', 'READ', 'ALLOW', 'manual_admin');
```

## 5. ES 目标字段规范

### 5.1 兼容原则

第一阶段不删除旧字段。必须做到：

1. 新字段双写。
2. 查询新字段优先，旧字段兜底。
3. fallback 命中打日志，观察稳定后再考虑下线旧字段。

### 5.2 `kb_document_*` chunk 索引

| 字段 | 类型 | 阶段 | 说明 |
| --- | --- | --- | --- |
| `content` | `text` | 保留 | BM25、证据、预览依赖 |
| `display_content` | `text` | 保留 | 展示内容 |
| `vector` | `dense_vector(1024), index=true` | 保留 | 语义召回 |
| `sparse_vector` | `rank_features` | 保留 | 稀疏召回 |
| `acl_tokens` | `keyword` | 新标准 | ES 权限投影 |
| `source_index` | `keyword` | 新增 | 实际物理索引 |
| `index_code` | `keyword` | 新增 | 业务索引编码 |
| `owner_unit_code` | `keyword` | 新增 | 归属单位 |
| `visible_unit_codes` | `keyword` | 新增 | 可见单位集合 |
| `permission_version` | `long` | 新增 | 权限投影版本 |
| `metadata.source` | `keyword` | 兼容保留 | 当前强依赖 |
| `metadata.title` | `text + keyword` | 兼容保留 | 标题检索 |
| `metadata.doc_type` | `keyword + text` | 兼容保留 | 当前查询依赖 |
| `metadata.acl_tokens` | `keyword` | 兼容保留 | 当前权限过滤依赖 |
| `metadata.owner_dept_id` | `keyword` | 兼容保留 | 当前单位字段 |
| `metadata.search_queries` | `text` | 过渡保留 | 现有关键词召回使用 |
| `metadata.publish_time` | `date` | 保留 | 展示/排序 |
| `metadata.is_latest` | `boolean` | 保留 | 版本过滤 |
| `colloquial_vector` | 不进入新索引 | 下线 | 主链路已废弃 |

### 5.3 `kb_doc_search_v2`

文档级预检索索引必须补齐索引和权限维度，否则会绕过角色索引授权。

| 字段 | 类型 |
| --- | --- |
| `doc_id` | `keyword` |
| `source` / `source_name` | `keyword` |
| `title` / `doc_title` | `text + keyword` |
| `document_number` | `keyword + text/ngram` |
| `doc_terms` | `text` |
| `search_text` | `text` |
| `search_aliases` | `keyword` |
| `source_index` | `keyword` |
| `index_code` | `keyword` |
| `doc_type` | `keyword` |
| `publish_time` | `date` |
| `is_latest` | `boolean` |
| `visibility` | `keyword` |
| `acl_tokens` | `keyword` |
| `owner_dept_id` | `keyword` |
| `owner_unit_code` | `keyword` |
| `visible_unit_codes` | `keyword` |
| `permission_version` | `long` |
| `security_level` | `integer`，第一阶段仅保留不启用 |
| `vector` | `dense_vector(1024), index=true` |

### 5.4 `kb_doc_meta_v3`

用于文档相似、文档向量和元信息展示。

| 字段 | 类型 |
| --- | --- |
| `doc_id` | `keyword` |
| `source` / `source_name` | `keyword` |
| `title` / `doc_title` | `text + keyword` |
| `summary` | `text, index=false` |
| `doc_vector` | `dense_vector(1024), index=true` |
| `source_index` | `keyword` |
| `index_code` | `keyword` |
| `doc_type` | `keyword` |
| `publish_time` | `date` |
| `is_latest` | `boolean` |
| `visibility` | `keyword` |
| `acl_tokens` | `keyword` |
| `owner_dept_id` | `keyword` |
| `owner_unit_code` | `keyword` |
| `visible_unit_codes` | `keyword` |
| `permission_version` | `long` |

### 5.5 `kb_qa_pairs_v2`

当前环境没有 `kb_doc_qa`，实际索引为 `kb_qa_pairs`。QA 参与召回时必须纳入同一权限投影。

| 字段 | 类型 |
| --- | --- |
| `qa_id` | `keyword` |
| `doc_id` / `doc_hash` | `keyword` |
| `doc_version` | `integer` |
| `source` | `keyword` |
| `title` / `doc_title` | `text + keyword` |
| `question` | `text` |
| `answer_content` | `text` |
| `answer_chunk_id` | `keyword` |
| `question_vector` | `dense_vector(1024), index=true` |
| `source_index` | `keyword` |
| `index_code` | `keyword` |
| `doc_type` | `keyword` |
| `publish_time` | `date` |
| `is_latest` | `boolean` |
| `visibility` | `keyword` |
| `acl_tokens` | `keyword` |
| `owner_dept_id` | `keyword` |
| `owner_unit_code` | `keyword` |
| `visible_unit_codes` | `keyword` |
| `permission_version` | `long` |

## 6. 实施任务

### 阶段 0：基线冻结

| 编号 | 任务 | 产出 | 验收 |
| --- | --- | --- | --- |
| S0-1 | 导出 ES alias/mapping/count | `baseline_es_*.json` | 所有目标索引有快照 |
| S0-2 | 统计关键字段存在率 | 字段填充率报告 | `acl_tokens/source_index/owner_dept_id/vector` 等有统计 |
| S0-3 | 保存典型查询样本 | 查询基线 | keyword/semantic/hybrid/similarity/QA/detail 均覆盖 |
| S0-4 | 准备权限测试身份 | 测试账号矩阵 | admin、普通角色、上级单位、本级单位、无权限用户 |

### 阶段 1：P0 手动 PostgreSQL 建表与授权

| 编号 | 任务 | 类型 | 验收 |
| --- | --- | --- | --- |
| S1-1 | 手动执行 `kb_index_acl_subjects` PostgreSQL 脚本 | DBA/运维 | 表和索引存在 |
| S1-2 | 插入最小角色索引授权数据 | DBA/运维 | `ROLE` 可匹配 `IndexAclSubjectService.principalKeys()` |
| S1-3 | 调用/验证索引 ACL 查询接口 | 已有代码 | 授权角色只返回允许索引 |
| S1-4 | 不启用自动 migration | 运维约束 | 生产仍按离线 SQL 审核执行 |

### 阶段 2：P0/P1 代码正确性修复

| 编号 | 任务 | 涉及模块 | 验收 |
| --- | --- | --- | --- |
| S2-1 | 修复 `SearchServiceV2` 后置权限 guardKey 语义 | `SearchServiceV2`、`PermissionGuard` | 使用稳定文档键，不再用机构名误判 |
| S2-2 | `SimilarityService` 接入索引 ACL 范围 | `SimilarityService`、`SearchIndexResolver`、`IndexAclGuard` | 相似推荐不越过角色可读索引 |
| S2-3 | 修复旧 `SearchController` 空指针条件 | `SearchController` | `fileName=null` 不异常 |
| S2-4 | 修复 analyze 固定 `kb_document_v1` | `SearchController` | 空 `kb_document_v1` 不影响分词 |
| S2-5 | 禁用/归档 `colloquial_vector` 历史脚本 | `scripts/*`、`ai_service/scripts/*` | 默认运维路径不再写该字段 |

### 阶段 3：新索引契约

| 编号 | 任务 | 涉及模块 | 验收 |
| --- | --- | --- | --- |
| S3-1 | 新建 `kb_document_template_v2` | ES / `es_setup.py` | 不含 `colloquial_vector`，权限字段为 keyword |
| S3-2 | 新建 `kb_doc_search_v2` | ES | `source_index/index_code/visible_unit_codes` 存在 |
| S3-3 | 新建 `kb_doc_meta_v3` | ES | `source_index/index_code/visible_unit_codes` 存在 |
| S3-4 | 新建 `kb_qa_pairs_v2` | ES | `acl_tokens` 为 keyword，QA 权限字段存在 |
| S3-5 | 写别名候选配置 | ES | 暂不切主读写 alias |

### 阶段 4：新入库双写

| 编号 | 任务 | 涉及模块 | 验收 |
| --- | --- | --- | --- |
| S4-1 | 区分 `requested_target_index/resolved_target_index` | Java 入库、Python 入库 | 最终 `source_index` 使用真实写入索引 |
| S4-2 | chunk 顶层写标准字段 | `rag_pipeline.py` | 新上传文档标准字段 100% |
| S4-3 | 继续写旧 `metadata.*` | `rag_pipeline.py` | 旧检索链路不受影响 |
| S4-4 | `doc_indexer.py` 写 `source_index/index_code/visible_unit_codes` | `doc_indexer.py` | `kb_doc_search/meta` 可按索引过滤 |
| S4-5 | QA 写标准权限字段 | QA 生成链路 | QA 召回可做同源权限过滤 |
| S4-6 | `searchQueries` 迁移到 `search_text/search_aliases`，同时过渡保留 `metadata.search_queries` | 入库与 doc_search | 关键词召回不下降 |

### 阶段 5：查询双读与权限过滤

| 编号 | 任务 | 涉及模块 | 验收 |
| --- | --- | --- | --- |
| S5-1 | 所有 chunk 查询使用 `resolvedIndexPattern` | 各 Recall Step | 无步骤回退扫全 `kb_document` |
| S5-2 | `doc_type` 双字段过滤 | Recall Step | `doc_type` 与 `metadata.doc_type.keyword` 均兼容 |
| S5-3 | `source/title/document_number` 双字段查询 | Recall/Result/Rerank | 新旧索引都能召回和展示 |
| S5-4 | `acl_tokens` 双字段兼容 | `EsRecallUtils`、`DocAclProjectionService` | 顶层和 `metadata` 均可用 |
| S5-5 | `visible_unit_codes` 加入权限过滤，但旧 `owner_dept_id` 兜底 | `EsRecallUtils` | 单位权限字段迁移可灰度 |
| S5-6 | `kb_doc_search_v2` 查询加 `source_index/index_code` 过滤 | `KeywordRecallStrategy`、`HybridRecallStrategy` | 文档级预筛不越权 |
| S5-7 | `kb_doc_meta_v3` 相似查询加 `source_index/index_code` 过滤 | `SimilarityService` | 相似文档不越权 |
| S5-8 | `kb_qa_pairs_v2` QA 查询加权限过滤 | QA 召回 | QA 不越权 |
| S5-9 | 如果启用文档类型 ACL，新增 doc_type ACL 决策服务 | 新开发 | 角色只看到授权 doc_type |

### 阶段 6：历史数据迁移

迁移方式固定为 scroll + bulk，不使用纯 `_reindex`，因为需要 DB join、字段重命名、单位权限计算。

| 编号 | 任务 | 数据来源 | 验收 |
| --- | --- | --- | --- |
| S6-1 | 迁移 `kb_document_*` 到 v2 物理索引 | 旧 ES + `kb_doc_registry` | count 一致，向量维度一致 |
| S6-2 | 重建 `kb_doc_search_v2` | 旧 chunk + DB | 每文档有 `source_index/index_code` |
| S6-3 | 重建 `kb_doc_meta_v3` | 旧 chunk/doc_vector + DB | 相似推荐字段完整 |
| S6-4 | 迁移 `kb_qa_pairs_v2` | 旧 QA + DB | QA 权限字段完整 |
| S6-5 | 迁移报告 | 脚本输出 | 成功/失败/跳过/字段缺失可审计 |

字段转换：

| 目标字段 | 来源 |
| --- | --- |
| `source_index` | `kb_doc_registry.target_index`，缺失时用旧 ES `_index` |
| `index_code` | `sys_index_routing.target_index` 推导，缺失时从索引名推导 |
| `owner_unit_code` | `kb_doc_registry.dept_code` |
| `owner_dept_id` | 同 `owner_unit_code`，用于当前代码兼容 |
| `visible_unit_codes` | `dept_code + 上级单位链` |
| `acl_tokens` | DB 权限模型重新计算，旧 ES 作为兜底 |
| `publish_time` | `kb_doc_registry.publish_time` 或旧 `metadata.publish_time` |
| `search_text/search_aliases` | 旧 `metadata.search_queries` 拆分/迁移 |

### 阶段 7：灰度 alias 切换

切换顺序：

1. `kb_doc_search` / `kb_doc_search_write` -> `kb_doc_search_v2`
2. `kb_doc_meta_read` / `kb_doc_meta_write` -> `kb_doc_meta_v3`
3. `kb_qa_read` / `kb_qa_write` -> `kb_qa_pairs_v2`
4. `kb_document` 读别名逐步指向新 `kb_document_*_v2`
5. 各物理写别名逐步切换

验收：

1. 搜索成功率正常。
2. P95/P99 不劣化。
3. 权限后置拦截数不异常升高。
4. 没有未授权索引命中。
5. 可用反向 alias 脚本一键回滚。

### 阶段 8：旧字段下线

下线条件：

1. fallback 日志连续观察期为 0。
2. 无生产流量调用旧 `SearchController` 风险路径。
3. `metadata.search_queries` 已完全迁移到 `kb_doc_search_v2`。
4. `colloquial_vector` 脚本确认停止。
5. 旧索引保留至少一个完整业务周期。

可下线候选：

| 字段 | 条件 |
| --- | --- |
| `colloquial_vector` | 已确认无脚本写入 |
| `metadata.search_queries` | `search_text/search_aliases` 完全承接 |
| `visible_depts` | `visible_unit_codes` 完全承接 |
| `metadata.dept_l2/l4/l6/l9/dept_code_full` | 单位链字段完全承接 |
| `doc_title` | `title` 完全承接且前端不依赖 |

## 7. 测试矩阵

### 7.1 权限测试

| 用户 | 角色 | 单位 | 预期 |
| --- | --- | --- | --- |
| 管理员 | admin | 任意 | 可看全部 |
| 普通用户 A | official_reader | A | 只能看授权索引 + 单位可见文档 |
| 普通用户 B | public_reader | B | 只能看 public + 单位可见文档 |
| 上级单位用户 | official_reader | A 上级 | 可看 A 单位文档 |
| 平级单位用户 | official_reader | A 平级 | 不可看 A 单位受限文档 |
| 无权限用户 | none | 任意 | 查不到受限文档 |
| doc_type 受限用户 | notice_reader | 任意 | 只可看授权 doc_type |

### 7.2 功能回归

| 功能 | 验收 |
| --- | --- |
| 关键词检索 | TopK 与基线可解释一致 |
| 语义检索 | KNN 正常，向量维度无错误 |
| 混合检索 | RRF/重排正常 |
| 文档级预筛 | 不越过 `source_index` 授权 |
| 相似文档 | 不扫全 `kb_document` |
| QA 召回 | QA 结果不越权 |
| 文档详情 | chunk 回查正常 |
| 文档版本 | `is_latest/doc_version` 正常 |
| 权限变更 | ES 投影失败可重试，DB 后置兜底有效 |

### 7.3 性能指标

| 指标 | 目标 |
| --- | --- |
| 每次查询访问 shard 数 | 相比扫 `kb_document` alias 减少或持平 |
| P95/P99 | 不劣化 |
| DB 后置校验耗时 | 批量校验，不退化为 N 次查询 |
| 后置权限拦截数 | 有监控，异常升高需回滚 |
| `__no_readable_index__` 比例 | 与授权配置一致 |

## 8. 回滚方案

### 8.1 回滚条件

1. 出现权限越权。
2. 搜索成功率明显下降。
3. P95/P99 明显劣化。
4. 文档详情或 QA 大面积为空。
5. 相似文档出现跨授权索引结果。

### 8.2 回滚动作

1. 读 alias 切回旧索引。
2. 写 alias 切回旧索引。
3. 查询层继续保留旧字段 fallback。
4. P0 手动建表不需要回滚，停用规则即可：

```sql
UPDATE public.kb_index_acl_subjects
SET is_active = 0, updated_at = CURRENT_TIMESTAMP
WHERE index_name IN ('kb_document_official', 'kb_document_public')
  AND subject_type = 'ROLE';
```

## 9. 最小可落地版本

如果要最快上线并控制风险，按以下最小闭环执行：

1. 手动执行 PostgreSQL `kb_index_acl_subjects` 建表。
2. 配置角色到物理索引的 ALLOW 规则。
3. 修复 `SimilarityService` 使用同一索引 ACL 范围。
4. 修复 `SearchServiceV2` 后置权限主键语义。
5. 新增 `source_index` 到 `kb_doc_search/kb_doc_meta/kb_qa`，并离线回填。
6. 主检索、doc_search、相似、QA 全部按可读物理索引过滤。
7. 保留所有旧字段，不做字段删除。

这个版本已经能覆盖：

1. 管理员看全部。
2. 角色看某些索引。
3. 单位权限通过 `acl_tokens` 和 `PermissionGuard` 生效。
4. ES 查询压力低于全部交给数据库过滤的方案。

## 10. 任务优先级总览

| 优先级 | 任务 |
| --- | --- |
| P0 | 手动 PG 建 `kb_index_acl_subjects`、配置角色索引权限 |
| P0 | 修复 `SimilarityService` 不得扫全 `kb_document` |
| P0 | 修复后置权限 guardKey 语义 |
| P1 | 新建 v2/v3 索引 mapping |
| P1 | 新入库双写 `source_index/index_code/owner_unit_code/visible_unit_codes` |
| P1 | `kb_doc_search/kb_doc_meta/kb_qa` 补权限字段 |
| P1 | 查询链路双读和权限过滤统一 |
| P2 | 历史数据 scroll+bulk 迁移 |
| P2 | alias 灰度切换 |
| P3 | 旧字段下线和脚本归档 |
