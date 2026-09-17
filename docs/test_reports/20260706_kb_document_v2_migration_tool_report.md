# kb_document_* v2 离线迁移工具实施报告

## 1. 本轮目标

在 ES 已恢复、历史权限字段值已确认基本补齐后，继续解决 `kb_document_*` 现有 mapping 不满足生产权限检索的问题。

本轮不执行真实迁移、不切 alias，只交付可 dry-run、可审计、可灰度执行的离线迁移工具。

## 2. 根因判断

在线审计已经确认：

- `kb_document_official/public/news/law` 的根级 `acl_tokens` 为 `text + keyword`，不是纯 `keyword`。
- `kb_document_notice/v1` 缺少根级 `acl_tokens` mapping。
- ES 不支持把已有字段从 `text` 原地改成 `keyword`。

因此根治方式只能是：

1. 创建新目标索引。
2. 使用正确 mapping。
3. scroll + bulk 迁移历史数据。
4. 校验 count、权限字段、向量字段。
5. 确认后通过 alias 原子切换。

## 3. 新增文件

- `ai_service/scripts/migrate_kb_document_index_v2.py`
- `ai_service/tools_and_tests/test_migrate_kb_document_index_v2.py`

## 4. 迁移工具能力

脚本支持：

```text
--source-index
--target-index
--batch-size
--limit
--dry-run
--execute
--create-target
--switch-alias
--output
--es-host
--es-user
--es-pass
--read-alias
--write-alias
--shards
--replicas
```

默认行为：

- 不创建索引。
- 不写入数据。
- 不切 alias。
- 不删除旧索引。

只有传入 `--execute` 才会真实写入。

只有同时传入 `--execute --switch-alias` 才会切 alias。

## 5. 目标 mapping 关键字段

| 字段 | 类型 | 原因 |
| --- | --- | --- |
| `content` | `text` + `ik_max_word/ik_smart` | BM25 检索 |
| `display_content` | `text` | 展示内容 |
| `vector` | `dense_vector` 1024 cosine | KNN 检索 |
| `sparse_vector` | `rank_features` | Sparse 检索 |
| `acl_tokens` | `keyword` | 权限前置过滤核心字段 |
| `source_index` | `keyword` | 角色可读索引过滤 |
| `index_code` | `keyword` | 索引/类型归属 |
| `owner_unit_code` | `keyword` | 归属单位 |
| `visible_unit_codes` | `keyword` | 单位链可见性过滤 |
| `permission_version` | `long` | 权限投影版本 |
| `metadata.acl_tokens` | `keyword` | 兼容历史读取 |
| `metadata.visible_unit_codes` | `keyword` | 兼容单位过滤 |
| `metadata.source_index` | `keyword` | 兼容历史字段 |

## 6. 数据转换策略

迁移时只写白名单字段，避免旧索引动态脏字段污染 v2。

权限字段策略：

- 顶层字段优先。
- 顶层缺失时使用 `metadata.*`。
- `acl_tokens` 仍缺失时写入 `["_NO_ACCESS"]`。
- `visible_unit_codes` 缺失时写入 `["global"]`。
- `source_index` 缺失时使用源物理索引名。
- `permission_version` 缺失时使用当前迁移时间戳。

向量字段策略：

- 有非空 `vector` 时写入。
- 缺失或空数组时不写入，避免 ES 拒绝空 `dense_vector`。

冗余字段策略：

- 不迁移 `colloquial_vector`。
- 不迁移 mapping 中未声明的动态字段。

## 7. 单元测试结果

执行命令：

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

## 8. 真实 ES dry-run

执行命令：

```bash
python -X utf8 ai_service/scripts/migrate_kb_document_index_v2.py \
  --es-host http://localhost:9200 \
  --source-index kb_document_news \
  --target-index kb_document_news_v2 \
  --batch-size 2 \
  --limit 5 \
  --sample 5 \
  --create-target \
  --dry-run \
  --output docs/test_reports/20260706_kb_document_news_v2_migration_dry_run.json
```

结果：

- scanned：5
- dry_run_writes：5
- written：0
- failed：0
- 未创建 `kb_document_news_v2`
- 未切 alias

安全验证：

```text
HEAD kb_document_news_v2 -> 404
```

说明 dry-run 没有创建目标索引。

dry-run 报告：

- `docs/test_reports/20260706_kb_document_news_v2_migration_dry_run.json`

## 9. 下一步建议

下一步可以进入小批量真实迁移，但必须先确认迁移窗口：

1. 暂停或冻结对应源索引写入。
2. 导出 alias、mapping、settings、count 基线。
3. 对单个低风险索引执行 `--create-target --execute` 创建 v2。
4. 执行 `--limit 1000 --execute` 小批量迁移。
5. 校验 v2 count、mapping、权限 token 查询、向量存在率。
6. 全量迁移。
7. 最后才执行 `--execute --switch-alias`。

建议先从 `kb_document_news` 开始，因为当前只有 16 条数据，适合作为迁移工具生产前验证样本。

