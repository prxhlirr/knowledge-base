# kb_document_official_v2 真实迁移报告

## 1. 本轮目标

执行全量迁移方案第 2 批：`kb_document_official -> kb_document_official_v2`。

本轮边界：

- 创建 `kb_document_official_v2`。
- 迁移 `kb_document_official` 全量 669 条数据。
- 不切换 alias。
- 不删除旧索引。
- 不改变当前线上读写路径。

## 2. 迁移前基线

已导出：

- `docs/test_reports/20260706_kb_document_official_before_aliases.json`
- `docs/test_reports/20260706_kb_document_official_before_mapping.json`
- `docs/test_reports/20260706_kb_document_official_before_settings.json`
- `docs/test_reports/20260706_kb_document_official_before_count.json`

迁移前状态：

| 项 | 值 |
| --- | --- |
| `kb_document_official` count | 669 |
| `kb_document_official_v2` | 不存在 |
| `kb_document` read alias | 指向 `kb_document_official` |
| `kb_document_official_write` write alias | 指向 `kb_document_official` |

旧索引核心问题：

- `acl_tokens` 为 `text + keyword`。
- 权限字段值已存在，但 mapping 不满足生产目标。

## 3. 执行命令

```bash
python -X utf8 ai_service/scripts/migrate_kb_document_index_v2.py \
  --es-host http://localhost:9200 \
  --source-index kb_document_official \
  --target-index kb_document_official_v2 \
  --batch-size 100 \
  --limit 0 \
  --sample 10 \
  --create-target \
  --execute \
  --output docs/test_reports/20260706_kb_document_official_v2_migration_execute.json
```

执行结果：

```text
scanned = 669
written = 669
failed = 0
switch_alias = false
```

执行报告：

- `docs/test_reports/20260706_kb_document_official_v2_migration_execute.json`

## 4. 自动 alias 清理

创建 `kb_document_official_v2` 时，目标索引会从 `kb_document_template` 自动继承 `kb_document` read alias。

脚本已自动移除该 alias：

```text
[kb_document v2 Migration] removed auto alias kb_document from kb_document_official_v2
```

这保证本轮“不切 alias”的边界成立，避免 `kb_document` 同时读到旧 official 和 official_v2。

## 5. 迁移后校验

校验报告：

- `docs/test_reports/20260706_kb_document_official_v2_validation.json`

count 对账：

| 索引 | count |
| --- | ---: |
| `kb_document_official` | 669 |
| `kb_document_official_v2` | 669 |

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

| 查询 | 命中数 |
| --- | ---: |
| `kb_document_official_v2 acl_tokens=_INTERNAL` | 669 |
| `kb_document_official_v2 acl_tokens=dept::620102` | 0 |
| `kb_document_official_v2 acl_tokens=_NO_ACCESS` | 0 |

alias 校验：

| 校验项 | 结果 |
| --- | ---: |
| `kb_document` alias 下 `source_index=kb_document_official` | 669 |
| `kb_document` alias 下 `source_index=kb_document_official AND acl_tokens.keyword=_INTERNAL` | 669 |

结论：

- `kb_document_official_v2` 数据完整。
- v2 mapping 已修正为权限过滤友好的纯 `keyword`。
- 当前线上 `kb_document` alias 仍只读旧 `kb_document_official`，没有重复召回。
- 当前 `kb_document_official_write` 仍指向旧 `kb_document_official`。

## 6. 回归测试

迁移工具测试：

```bash
python -X utf8 ai_service/tools_and_tests/test_migrate_kb_document_index_v2.py
```

结果：

```text
10 个测试全部通过。
```

语法检查：

```bash
python -m py_compile ai_service/scripts/migrate_kb_document_index_v2.py ai_service/tools_and_tests/test_migrate_kb_document_index_v2.py
```

结果：通过。

Java 编译：

```bash
mvn -q -DskipTests compile
```

结果：通过。

## 7. 下一步建议

`kb_document_official_v2` 已具备切换条件。

下一步建议执行 official alias 切换演练：

```bash
python -X utf8 ai_service/scripts/migrate_kb_document_index_v2.py \
  --es-host http://localhost:9200 \
  --source-index kb_document_official \
  --target-index kb_document_official_v2 \
  --batch-size 100 \
  --limit 0 \
  --sample 10 \
  --create-target \
  --execute \
  --switch-alias \
  --output docs/test_reports/20260706_kb_document_official_v2_alias_switch_execute.json
```

切换后必须校验：

- `kb_document` read alias 指向 `kb_document_official_v2`。
- `kb_document_official_write` write alias 指向 `kb_document_official_v2`。
- `kb_document` alias 下 `source_index=kb_document_official` 仍为 669，不应变成 1338。

