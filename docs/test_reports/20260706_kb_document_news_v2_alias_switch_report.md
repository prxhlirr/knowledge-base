# kb_document_news_v2 alias 切换演练报告

## 1. 本轮目标

对已完成迁移的 `kb_document_news_v2` 执行 alias 切换演练。

本轮边界：

- 只切换 `kb_document_news` 这一组读写 alias。
- 不删除旧索引。
- 不迁移其它 `kb_document_*` 索引。
- 切换后立即校验重复召回、权限查询和写 alias 指向。

## 2. 切换前基线

已导出：

- `docs/test_reports/20260706_kb_document_news_alias_switch_before_aliases.json`
- `docs/test_reports/20260706_kb_document_news_alias_switch_before_old_count.json`
- `docs/test_reports/20260706_kb_document_news_alias_switch_before_new_count.json`

切换前状态：

| 项 | 值 |
| --- | --- |
| `kb_document_news` count | 16 |
| `kb_document_news_v2` count | 16 |
| `kb_document` read alias | 指向 `kb_document_news` |
| `kb_document_news_write` write alias | 指向 `kb_document_news` |

## 3. 执行命令

```bash
python -X utf8 ai_service/scripts/migrate_kb_document_index_v2.py \
  --es-host http://localhost:9200 \
  --source-index kb_document_news \
  --target-index kb_document_news_v2 \
  --batch-size 8 \
  --limit 1000 \
  --sample 3 \
  --create-target \
  --execute \
  --switch-alias \
  --output docs/test_reports/20260706_kb_document_news_v2_alias_switch_execute.json
```

执行结果：

```text
scanned = 16
written = 16
failed = 0
switch_alias = true
```

执行报告：

- `docs/test_reports/20260706_kb_document_news_v2_alias_switch_execute.json`

## 4. Alias 原子切换动作

实际执行的 alias actions：

```json
[
  {
    "remove": {
      "index": "kb_document_news",
      "alias": "kb_document"
    }
  },
  {
    "remove": {
      "index": "kb_document_news",
      "alias": "kb_document_news_write"
    }
  },
  {
    "add": {
      "index": "kb_document_news_v2",
      "alias": "kb_document"
    }
  },
  {
    "add": {
      "index": "kb_document_news_v2",
      "alias": "kb_document_news_write",
      "is_write_index": true
    }
  }
]
```

## 5. 切换后校验

校验报告：

- `docs/test_reports/20260706_kb_document_news_v2_alias_switch_validation.json`

校验结果：

| 校验项 | 结果 |
| --- | ---: |
| `kb_document_news` count | 16 |
| `kb_document_news_v2` count | 16 |
| 通过 `kb_document` alias 查询 `source_index=kb_document_news` | 16 |
| 通过 `kb_document` alias 查询 `source_index=kb_document_news AND acl_tokens=dept::620102` | 16 |
| 通过 `kb_document` alias 查询 `source_index=kb_document_news AND acl_tokens.keyword=dept::620102` | 0 |

结论：

- 没有出现旧新索引重复召回，新闻数据在 `kb_document` alias 下仍为 16 条。
- `kb_document` read alias 已切到 `kb_document_news_v2`。
- `kb_document_news_write` write alias 已切到 `kb_document_news_v2`。
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
        "index": "kb_document_news_v2",
        "alias": "kb_document"
      }
    },
    {
      "remove": {
        "index": "kb_document_news_v2",
        "alias": "kb_document_news_write"
      }
    },
    {
      "add": {
        "index": "kb_document_news",
        "alias": "kb_document"
      }
    },
    {
      "add": {
        "index": "kb_document_news",
        "alias": "kb_document_news_write",
        "is_write_index": true
      }
    }
  ]
}
```

回滚后需再次校验：

```text
kb_document alias 下 source_index=kb_document_news count = 16
kb_document_news_write 指向 kb_document_news
```

## 8. 下一步建议

`kb_document_news_v2` 已完成从迁移到 alias 切换的完整闭环。

下一步建议迁移 `kb_document_public`：

- 当前数据量 22 条，仍属于低风险索引。
- 可以复用同一套流程。
- 先迁移并校验，不立即切 alias；确认无异常后再切换。

