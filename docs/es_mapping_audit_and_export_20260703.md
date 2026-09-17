# ES Mapping 审计与导出说明

## 1. 审计目标

基于当前真实 ES 状态，分析现有索引 mapping 是否合理，判断是否还需要继续修正，并将 template 与各索引 mapping 导出到文件。

本次只做只读审计与文件导出，不修改 ES 数据。

## 2. 导出结果

导出根目录：

- `docs/es_exports/20260703_mapping_audit/`

核心文件：

| 文件/目录 | 说明 |
| --- | --- |
| `docs/es_exports/20260703_mapping_audit/export_manifest.json` | 本次导出清单 |
| `docs/es_exports/20260703_mapping_audit/raw/` | ES 原始响应 |
| `docs/es_exports/20260703_mapping_audit/templates/` | 拆分后的 index template |
| `docs/es_exports/20260703_mapping_audit/mappings/` | 各索引 mapping |
| `docs/es_exports/20260703_mapping_audit/settings/` | 各索引 settings |
| `docs/es_exports/20260703_mapping_audit/mapping_summary.json` | 结构化摘要 |
| `docs/es_exports/20260703_mapping_audit/mapping_summary.md` | Markdown 摘要 |

已导出的 template：

- `docs/es_exports/20260703_mapping_audit/templates/kb_template.json`
- `docs/es_exports/20260703_mapping_audit/templates/kb_document_template.json`

legacy template：

- `/_template/kb*` 返回 404，响应体为空对象。
- 结论：当前没有匹配 `kb*` 的 legacy template。

已导出的索引 mapping：

- `kb_doc_meta`
- `kb_doc_meta_v2`
- `kb_doc_search_v1`
- `kb_document_law`
- `kb_document_news`
- `kb_document_notice`
- `kb_document_official`
- `kb_document_public`
- `kb_document_v1`
- `kb_qa_pairs`
- `kb_qa_pairs_v2`

## 3. 当前 Alias 状态

| alias | index | is_write_index |
| --- | --- | --- |
| `kb_document` | `kb_document_public` | `-` |
| `kb_document_public_write` | `kb_document_public` | `true` |
| `kb_document` | `kb_document_official` | `-` |
| `kb_document_official_write` | `kb_document_official` | `true` |
| `kb_document` | `kb_document_law` | `-` |
| `kb_document_law_write` | `kb_document_law` | `true` |
| `kb_document` | `kb_document_notice` | `-` |
| `kb_document_notice_write` | `kb_document_notice` | `true` |
| `kb_document` | `kb_document_news` | `-` |
| `kb_document_news_write` | `kb_document_news` | `true` |
| `kb_document` | `kb_document_v1` | `-` |
| `kb_document_v1_write` | `kb_document_v1` | `true` |
| `kb_doc_meta_read` | `kb_doc_meta_v2` | `-` |
| `kb_doc_meta_write` | `kb_doc_meta_v2` | `true` |
| `kb_doc_search` | `kb_doc_search_v1` | `-` |
| `kb_doc_search_write` | `kb_doc_search_v1` | `true` |
| `kb_qa_read` | `kb_qa_pairs_v2` | `-` |
| `kb_qa_write` | `kb_qa_pairs_v2` | `true` |

## 4. Mapping 合理性结论

### 4.1 总体结论

当前 mapping 已经可以支撑现阶段权限检索，但还不是完全理想的生产终态。

可以继续运行的原因：

- `kb_doc_search_v1` 的权限字段已经是 `keyword`。
- `kb_qa_pairs_v2` 的权限字段已经是 `keyword`。
- `kb_document_*` 的 `source_index/index_code/owner_unit_code/visible_unit_codes/permission_version` 根级字段已经存在。
- 当前 QA 读写 alias 已经切换到 v2。

仍建议后续修正的原因：

- 多个历史 `kb_document_*` 索引的根级 `acl_tokens` 是 `text + keyword`，不是纯 `keyword`。
- `kb_doc_meta_v2` 的 `source_index/index_code/owner_unit_code/visible_unit_codes` 是 `text + keyword`，不是纯 `keyword`。
- 多数业务索引仍是 `shards=1, replicas=0`，不适合生产高可用和亿级扩展。
- `kb_qa_pairs_v2` 中存在 `content/doc_title/vector/security_level` 等非 QA 核心字段，属于 template 污染或历史兼容字段，不是当前生产 blocker，但不够干净。

## 5. 分索引结论

### 5.1 `kb_qa_pairs_v2`

现状：

- `question_vector`: `dense_vector`, `dims=1024`, `similarity=cosine`
- `acl_tokens`: `keyword`
- `source_index`: `keyword`
- `visible_unit_codes`: `keyword`
- `permission_version`: `long`
- `doc_version`: `integer`
- `is_latest`: `boolean`

结论：

- QA v2 已满足当前权限过滤和检索要求。
- 暂不需要继续迁移。

注意：

- `content/doc_title/vector/security_level` 对 QA 核心链路不是必需字段。
- 当前不建议为了清理冗余字段再迁移 QA v3，因为收益低于风险。

### 5.2 `kb_qa_pairs`

现状：

- 旧索引仍保留。
- `acl_tokens/source_index/index_code/owner_unit_code/visible_unit_codes` 为 `text + keyword`。

结论：

- 仅作为回滚保留。
- 不应再作为写入或常规读取目标。
- 观察期结束后可 snapshot 后关闭或删除。

### 5.3 `kb_doc_search_v1`

现状：

- `acl_tokens/source_index/index_code/owner_unit_code/visible_unit_codes` 均为 `keyword`。
- `publish_time` 为 `date`。
- `tags` 为 `keyword`。
- `title` 使用 `ik_max_word`，并带 `keyword/ngram` 子字段。

结论：

- 当前 mapping 合理。
- 暂不需要新建 v2。
- 后续只需要保证历史数据字段完整和新写入持续带权限字段。

### 5.4 `kb_doc_meta_v2`

现状：

- `doc_vector`: `dense_vector`, `dims=1024`, `similarity=cosine`
- `acl_tokens`: `keyword`
- `source_index/index_code/owner_unit_code/visible_unit_codes`: `text + keyword`
- `permission_version`: `long`

结论：

- 当前比旧 `kb_doc_meta` 已明显改善，因为 `doc_vector` 已是 dense_vector。
- 但单位/索引权限字段不是纯 `keyword`，不够理想。

建议：

- 短期：代码查询时必须使用 `.keyword` 或同时兼容根字段和 `.keyword`。
- 中期：如果 `kb_doc_meta_v2` 会承担权限过滤或高频聚合，建议迁移到 `kb_doc_meta_v3`，将这些字段改成纯 `keyword`。
- 如果 `kb_doc_meta_v2` 只用于展示/去重/低频管理查询，可暂缓迁移。

### 5.5 `kb_doc_meta`

现状：

- `doc_vector` 是 `float`，不是 dense_vector。
- alias 已指向 `kb_doc_meta_v2`。

结论：

- 旧索引只应作为历史保留或回滚目标。
- 不建议继续修正旧索引。

### 5.6 `kb_document_*`

涉及索引：

- `kb_document_public`
- `kb_document_official`
- `kb_document_law`
- `kb_document_notice`
- `kb_document_news`
- `kb_document_v1`

现状：

- `content`: `text`, `ik_max_word`, `search_analyzer=ik_smart`
- `vector`: `dense_vector`, `dims=1024`, `similarity=cosine`
- `sparse_vector`: `rank_features`
- `source_index/index_code/owner_unit_code/visible_unit_codes`: 多数为根级 `keyword`
- `permission_version`: `long`
- `acl_tokens`: 部分索引为 `text + keyword`，部分索引根级缺失但 `metadata.acl_tokens` 存在
- `metadata.source_index/index_code/owner_unit_code/visible_unit_codes`: 部分索引为 `text`

结论：

- 主检索字段基本可用。
- 权限字段存在不一致，尤其是 `acl_tokens`。
- 如果查询侧已经兼容 `acl_tokens.keyword`，可以短期运行。
- 从长期生产标准看，主文档索引最终应迁移到统一 mapping。

建议：

- 短期不强制全量迁移 `kb_document_*`。
- 先做字段缺失审计和离线补全。
- 对仍为空的业务索引，可以删除重建或直接保持 template 约束。
- 对已有数据的索引，不建议为了 `acl_tokens text+keyword` 马上全量迁移，除非 Java 检索侧无法稳定兼容 `.keyword`。

## 6. 是否还需要做其他修正

### 6.1 现在必须做的修正

没有发现必须立刻阻断上线的 mapping 问题。

前提是：

- 检索代码对 `acl_tokens` 兼容 `.keyword`。
- 历史数据权限字段补齐。
- 新写入链路持续写入根级权限字段。

### 6.2 建议尽快做的修正

| 优先级 | 项目 | 原因 | 方式 |
| --- | --- | --- | --- |
| P1 | `kb_document_*` 历史数据权限字段补齐 | 防止历史数据无权限字段导致误拒绝或误放行 | 原索引 bulk update |
| P1 | `kb_doc_meta_v2` 查询侧统一 `.keyword` | 当前字段是 `text + keyword` | 代码兼容或 v3 迁移 |
| P1 | 审计 Java 主检索 ACL filter 是否兼容 `.keyword` | 主索引 `acl_tokens` 多为 `text + keyword` | 代码审计 |
| P2 | 统一主索引 template 和现有物理索引差异 | 防止新旧索引行为不一致 | 新业务索引按 template 创建 |
| P2 | 分片副本参数化 | 当前多数 `1 shard / 0 replica` | 配置化 + 新索引生效 |

### 6.3 暂不建议做的修正

| 项目 | 原因 |
| --- | --- |
| 立即迁移所有 `kb_document_*` 到 v2 | 风险大，收益主要是类型洁净，不是当前 blocker |
| 为清理 QA 冗余字段再建 `kb_qa_pairs_v3` | QA v2 已满足权限和检索，冗余字段不影响当前功能 |
| 修改已存在字段类型 | ES 不支持原地修改，只能新建索引迁移 |

## 7. 后续判断规则

如果只是新增字段或补历史值：

- 使用 `put_mapping + bulk update`。

如果要修改以下内容：

- 字段类型
- analyzer/search_analyzer
- dense_vector dims/similarity
- shard 数
- 字段删除

必须：

- 新建 vN 索引
- 迁移数据
- 切 alias
- 保留旧索引观察期
