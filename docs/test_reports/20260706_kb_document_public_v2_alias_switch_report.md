# kb_document_public_v2 alias 切换演练报告

## 1. 本轮目标

对已完成迁移的 `kb_document_public_v2` 执行 alias 切换演练。

本轮边界：

- 只切换 `kb_document_public` 这一组读写 alias。
- 不删除旧索引。
- 不迁移其它 `kb_document_*` 索引。
- 切换后立即校验重复召回、权限查询和写 alias 指向。

## 2. 切换前基线

已导出：

- `docs/test_reports/20260706_kb_document_public_alias_switch_before_aliases.json`
- `docs/test_reports/20260706_kb_document_public_alias_switch_before_old_count.json`
- `docs/test_reports/20260706_kb_document_public_alias_switch_before_new_count.json`

切换前状态：

| 项 | 值 |
| --- | --- |
| `kb_document_public` count | 22 |
| `kb_document_public_v2` count | 22 |
| `kb_document` read alias | 指向 `kb_document_public` |
| `kb_document_public_write` write alias | 指向 `kb_document_public` |

## 3. 执行命令

```bash
python -X utf8 ai_service/scripts/migrate_kb_document_index_v2.py \
  --es-host http://localhost:9200 \
  --source-index kb_document_public \
  --target-index kb_document_public_v2 \
  --batch-size 10 \
  --limit 1000 \
  --sample 3 \
  --create-target \
  --execute \
  --switch-alias \
  --output docs/test_reports/20260706_kb_document_public_v2_alias_switch_execute.json
```

执行结果：

```text
scanned = 22
written = 22
failed = 0
switch_alias = true
```

执行报告：

- `docs/test_reports/20260706_kb_document_public_v2_alias_switch_execute.json`

## 4. Alias 原子切换动作

实际执行的 alias actions：

```json
[
  {
    "remove": {
      "index": "kb_document_public",
      "alias": "kb_document"
    }
  },
  {
    "remove": {
      "index": "kb_document_public",
      "alias": "kb_document_public_write"
    }
  },
  {
    "add": {
      "index": "kb_document_public_v2",
      "alias": "kb_document"
    }
  },
  {
    "add": {
      "index": "kb_document_public_v2",
      "alias": "kb_document_public_write",
      "is_write_index": true
    }
  }
]
```

## 5. 切换后校验

校验报告：

- `docs/test_reports/20260706_kb_document_public_v2_alias_switch_validation.json`

校验结果：

| 校验项 | 结果 |
| --- | ---: |
| `kb_document_public` count | 22 |
| `kb_document_public_v2` count | 22 |
| 通过 `kb_document` alias 查询 `source_index=kb_document_public` | 22 |
| 通过 `kb_document` alias 查询 `source_index=kb_document_public AND acl_tokens=_INTERNAL` | 22 |
| 通过 `kb_document` alias 查询 `source_index=kb_document_public AND acl_tokens.keyword=_INTERNAL` | 0 |

结论：

- 没有出现旧新索引重复召回，public 数据在 `kb_document` alias 下仍为 22 条。
- `kb_document` read alias 已切到 `kb_document_public_v2`。
- `kb_document_public_write` write alias 已切到 `kb_document_public_v2`。
- v2 是纯 `keyword` mapping，因此 `acl_tokens.keyword` 不命中是正常结果。
- 应用侧当前同时查询 `acl_tokens` 和 `acl_tokens.keyword`，可兼容旧索引与 v2 新索引。

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

## 7. 回滚方案

当前切换校验通过，因此未执行回滚。

如需回滚，可执行以下 alias actions：

```json
{
  "actions": [
    {
      "remove": {
        "index": "kb_document_public_v2",
        "alias": "kb_document"
      }
    },
    {
      "remove": {
        "index": "kb_document_public_v2",
        "alias": "kb_document_public_write"
      }
    },
    {
      "add": {
        "index": "kb_document_public",
        "alias": "kb_document"
      }
    },
    {
      "add": {
        "index": "kb_document_public",
        "alias": "kb_document_public_write",
        "is_write_index": true
      }
    }
  ]
}
```

回滚后需再次校验：

```text
kb_document alias 下 source_index=kb_document_public count = 22
kb_document_public_write 指向 kb_document_public
```

## 8. 当前迁移进度

| 索引 | 状态 |
| --- | --- |
| `kb_document_news` | 已迁移并切换到 `kb_document_news_v2` |
| `kb_document_public` | 已迁移并切换到 `kb_document_public_v2` |
| `kb_document_official` | 未迁移，当前 669 条 |
| `kb_document_law` | 未迁移，当前 0 条 |
| `kb_document_notice` | 未迁移，当前 0 条 |
| `kb_document_v1` | 未迁移，当前 0 条 |

## 9. 下一步建议

下一步建议迁移 `kb_document_official`。

原因：

- `news` 和 `public` 两个低风险索引已完成完整闭环。
- `official` 是当前剩余唯一有数据的大索引，当前约 669 条。
- 迁移前应再次导出基线，并先执行“不切 alias”的真实迁移校验。

