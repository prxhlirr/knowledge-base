# kb_qa_pairs_v2 Alias 切换前门禁报告

## 1. 门禁目标

本报告用于判断 `kb_qa_pairs_v2` 是否可以进入 `kb_qa_read/kb_qa_write` alias 切换阶段。

本次只做门禁检查，不执行 alias 切换。

## 2. ES 数据一致性检查

| 检查项 | 旧索引 `kb_qa_pairs` | 新索引 `kb_qa_pairs_v2` | 结果 |
| --- | ---: | ---: | --- |
| 文档数量 | `444` | `444` | 通过 |

结论：新旧 QA 索引数据量一致。

## 3. v2 关键字段完整性检查

| 字段 | 缺失数量 | 结果 |
| --- | ---: | --- |
| `question` | `0` | 通过 |
| `question_vector` | `0` | 通过 |
| `acl_tokens` | `0` | 通过 |
| `source_index` | `0` | 通过 |
| `visible_unit_codes` | `0` | 通过 |
| `permission_version` | `0` | 通过 |

结论：`kb_qa_pairs_v2` 中权限过滤、索引权限过滤和向量检索所需字段均无缺失。

## 4. v2 Mapping 检查

| 字段 | 实际类型 | 关键配置 | 结果 |
| --- | --- | --- | --- |
| `question` | `text` | `analyzer=ik_max_word` | 通过 |
| `question_vector` | `dense_vector` | `dims=1024`, `similarity=cosine` | 通过 |
| `acl_tokens` | `keyword` | 精确权限过滤 | 通过 |
| `source_index` | `keyword` | 角色可读索引过滤 | 通过 |
| `visible_unit_codes` | `keyword` | 单位权限过滤 | 通过 |
| `permission_version` | `long` | 权限版本追踪 | 通过 |
| `doc_hash` | `keyword` | 文档关联 | 通过 |
| `owner_unit_code` | `keyword` | 文档所属单位 | 通过 |
| `index_code` | `keyword` | 业务索引编码 | 通过 |
| `is_latest` | `boolean` | 最新版本过滤 | 通过 |
| `doc_version` | `integer` | 文档版本 | 通过 |

结论：`kb_qa_pairs_v2` 已消除旧索引 `acl_tokens` 动态映射为 `text+keyword` 带来的精确过滤不稳定问题，权限字段使用 `keyword`，适合 filter context 与缓存。

## 5. 权限字段分布抽样

### 5.1 `source_index`

| key | doc_count |
| --- | ---: |
| `kb_document_official` | `401` |
| `kb_document_news` | `25` |
| `kb_document_public` | `18` |

### 5.2 `acl_tokens`

| key | doc_count |
| --- | ---: |
| `_INTERNAL` | `419` |
| `dept::62` | `25` |
| `dept::6201` | `25` |
| `dept::620102` | `25` |
| `dept::62010290` | `25` |
| `user::god-admin` | `25` |

### 5.3 `visible_unit_codes`

| key | doc_count |
| --- | ---: |
| `global` | `419` |
| `62` | `25` |
| `6201` | `25` |
| `620102` | `25` |
| `62010290` | `25` |

结论：v2 权限 token 已补齐，且能表达内部公开、部门链路和用户特权三类权限。

## 6. 当前 Alias 状态

| alias | index | is_write_index |
| --- | --- | --- |
| `kb_qa_read` | `kb_qa_pairs` | `-` |
| `kb_qa_write` | `kb_qa_pairs` | `true` |

结论：当前线上读写入口仍指向旧索引，本次门禁检查未影响线上链路。

## 7. 代码兼容性检查

执行命令：

```powershell
python -X utf8 ai_service\tools_and_tests\test_qa_source_index_filter.py
```

执行结果：

| 测试项 | 结果 |
| --- | --- |
| `test_filter_uses_source_index_and_keyword_variant` | 通过 |
| `test_filter_ignores_alias_or_wildcard_to_avoid_guessing` | 通过 |
| `test_filter_ignores_non_document_indexes` | 通过 |
| `test_qa_acl_filter_matches_keyword_variant_for_dynamic_mapping` | 通过 |
| `test_qa_acl_filter_keeps_super_admin_bypass` | 通过 |
| `test_qa_acl_filter_is_fail_closed_when_tokens_missing` | 通过 |
| `test_qa_response_source_projection_includes_permission_fields` | 通过 |
| `test_qa_knn_candidates_uses_safe_default_and_topk_floor` | 通过 |
| `test_qa_knn_candidates_accepts_operational_override` | 通过 |
| `test_qa_knn_candidates_falls_back_for_invalid_env` | 通过 |
| `test_qa_knn_query_uses_candidate_helper_not_literal_50` | 通过 |
| `test_qa_paths_reuse_shared_acl_filter` | 通过 |

结论：应用侧 QA KNN 与 BM25 查询路径已经复用统一 ACL filter，且同时兼容旧索引的 `acl_tokens.keyword/source_index.keyword` 与新索引的纯 `keyword` 字段。

## 8. 门禁结论

`kb_qa_pairs_v2` 满足进入 alias 切换阶段的必要条件：

- 新旧索引文档数量一致。
- v2 权限、检索和版本字段无缺失。
- v2 mapping 类型符合权限过滤与检索性能要求。
- KNN/BM25 直查权限验证已经通过。
- 应用代码侧 QA 查询兼容旧索引和 v2 索引。
- 当前 alias 尚未切换，具备可控切换条件。

## 9. 下一步执行建议

建议下一步单独执行 alias 原子切换：

1. 切换前再次确认 `kb_qa_pairs_v2` count 与关键字段缺失数。
2. 原子执行 alias 更新：
   - 移除 `kb_qa_read -> kb_qa_pairs`
   - 新增 `kb_qa_read -> kb_qa_pairs_v2`
   - 移除 `kb_qa_write -> kb_qa_pairs`
   - 新增 `kb_qa_write -> kb_qa_pairs_v2` 且 `is_write_index=true`
3. 切换后立即验证 alias 状态。
4. 切换后立即执行 QA KNN/BM25 正反向权限回归。
5. 保留旧索引 `kb_qa_pairs`，不要立即删除，作为回滚目标。

## 10. 回滚策略

如果切换后出现 QA 检索异常，可以原子回滚 alias：

- `kb_qa_read` 从 `kb_qa_pairs_v2` 切回 `kb_qa_pairs`
- `kb_qa_write` 从 `kb_qa_pairs_v2` 切回 `kb_qa_pairs`，并恢复 `is_write_index=true`

回滚后重新执行 QA KNN/BM25 权限回归，确认线上查询恢复。
