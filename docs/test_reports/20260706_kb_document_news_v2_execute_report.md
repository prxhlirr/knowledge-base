# kb_document_news_v2 小批量真实迁移报告

## 1. 本轮目标

对低风险索引 `kb_document_news` 执行 v2 小批量真实迁移验证。

本轮边界：

- 创建 `kb_document_news_v2`。
- 迁移 `kb_document_news` 全量 16 条数据。
- 不切换 alias。
- 不删除旧索引。
- 不影响现有线上读写路径。

## 2. 迁移前基线

已导出：

- `docs/test_reports/20260706_kb_document_news_before_aliases.json`
- `docs/test_reports/20260706_kb_document_news_before_mapping.json`
- `docs/test_reports/20260706_kb_document_news_before_settings.json`
- `docs/test_reports/20260706_kb_document_news_before_count.json`

迁移前 count：

```text
kb_document_news = 16
kb_document_news_v2 = 404，不存在
```

旧索引核心问题：

- `acl_tokens` 为 `text + keyword`。
- 权限查询直接查 `acl_tokens` 会漏召回。

## 3. 执行命令

```bash
python -X utf8 ai_service/scripts/migrate_kb_document_index_v2.py \
  --es-host http://localhost:9200 \
  --source-index kb_document_news \
  --target-index kb_document_news_v2 \
  --batch-size 8 \
  --limit 1000 \
  --sample 5 \
  --create-target \
  --execute \
  --output docs/test_reports/20260706_kb_document_news_v2_migration_execute.json
```

执行结果：

```text
scanned = 16
written = 16
failed = 0
switch_alias = false
```

迁移执行报告：

- `docs/test_reports/20260706_kb_document_news_v2_migration_execute.json`

## 4. 中途发现的问题

创建 `kb_document_news_v2` 后发现：虽然脚本没有执行 `--switch-alias`，但 ES 当前存在 `kb_document_template`，新建 `kb_document_*` 会自动继承：

```json
{
  "aliases": {
    "kb_document": {}
  }
}
```

因此 `kb_document_news_v2` 被自动加入了 `kb_document` 读别名。

风险：

- `kb_document` 读别名会同时包含旧 `kb_document_news` 和新 `kb_document_news_v2`。
- 如果应用此时查询 `kb_document`，同一批新闻 chunk 可能重复召回。
- 这不符合“不切 alias”的迁移边界。

已执行修复：

```json
{
  "actions": [
    {
      "remove": {
        "index": "kb_document_news_v2",
        "alias": "kb_document"
      }
    }
  ]
}
```

结果：

```text
acknowledged = true
```

脚本同步修复：

- `migrate_kb_document_index_v2.py` 新增 `remove_auto_read_alias`。
- 未传入 `--switch-alias` 时，创建目标索引后自动移除模板继承到 v2 的读别名。
- alias action 去掉 ES 8.6 不支持的 `ignore_unavailable`。

## 5. 迁移后校验

校验报告：

- `docs/test_reports/20260706_kb_document_news_v2_validation.json`

count 对账：

| 索引 | count |
| --- | ---: |
| `kb_document_news` | 16 |
| `kb_document_news_v2` | 16 |

v2 mapping：

| 字段 | 类型 |
| --- | --- |
| `acl_tokens` | `keyword` |
| `source_index` | `keyword` |
| `visible_unit_codes` | `keyword` |
| `permission_version` | `long` |
| `metadata.acl_tokens` | `keyword` |
| `metadata.visible_unit_codes` | `keyword` |
| `vector` | `dense_vector` |

权限 token 查询：

| 查询字段 | token | 命中数 |
| --- | --- | ---: |
| `acl_tokens` | `dept::620102` | 16 |
| `acl_tokens.keyword` | `dept::620102` | 0 |
| `metadata.acl_tokens` | `dept::620102` | 16 |
| `metadata.acl_tokens.keyword` | `dept::620102` | 0 |

结论：

- v2 中 `acl_tokens` 已是纯 `keyword`。
- 应用侧过渡兼容的 `acl_tokens.keyword` 对 v2 不命中是正常现象。
- 现有 DSL 同时查询 `acl_tokens` 和 `acl_tokens.keyword`，因此兼容旧索引和 v2 新索引。

alias 校验：

- `kb_document_news_v2` 当前没有挂载 `kb_document`。
- `kb_document_news` 仍挂载 `kb_document`。
- `kb_document_news_write` 仍指向 `kb_document_news`。

## 6. 回归测试

执行：

```bash
python -X utf8 ai_service/tools_and_tests/test_migrate_kb_document_index_v2.py
```

结果：

```text
PASS test_mapping_uses_keyword_for_permission_fields
PASS test_build_target_source_promotes_permission_fields_to_root_and_metadata
PASS test_build_target_source_fail_closed_when_acl_missing
PASS test_ensure_target_index_dry_run_does_not_create
PASS test_ensure_target_index_execute_creates_with_mapping
PASS test_remove_auto_read_alias_removes_template_alias_before_switch
PASS test_migrate_batch_dry_run_does_not_bulk_write
PASS test_switch_aliases_is_gated_by_execute_and_switch_alias
PASS test_parse_args_accepts_runbook_options
PASS test_build_report_contains_structured_stats
```

语法检查：

```bash
python -m py_compile ai_service/scripts/migrate_kb_document_index_v2.py ai_service/tools_and_tests/test_migrate_kb_document_index_v2.py
```

结果：通过。

## 7. 下一步建议

下一步有两种路径：

1. 对 `kb_document_news_v2` 做 alias 切换演练。
2. 暂不切换，继续迁移下一个小索引或中等索引。

从生产稳妥性看，建议先执行 `kb_document_news_v2` alias 切换演练，因为：

- count 已一致。
- mapping 已正确。
- 权限 token 查询已正确。
- 数据量小，回滚成本最低。

切换前仍需再次确认：

- 当前无新闻索引写入任务。
- Java 检索读取 `kb_document` alias。
- 如切换失败，alias 可立即切回 `kb_document_news`。

