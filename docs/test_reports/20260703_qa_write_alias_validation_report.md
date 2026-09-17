# QA 写 Alias 验证报告

## 1. 验证目标

验证 `kb_qa_write` 写 alias 在切换后是否真正写入 `kb_qa_pairs_v2`，并确认旧索引 `kb_qa_pairs` 不再接收新增 QA 数据。

本次使用临时测试文档验证，验证完成后已删除测试文档。

## 2. 验证前状态

### 2.1 Alias 状态

| alias | index | is_write_index |
| --- | --- | --- |
| `kb_qa_read` | `kb_qa_pairs_v2` | `-` |
| `kb_qa_write` | `kb_qa_pairs_v2` | `true` |

### 2.2 数量基线

| 索引 | 验证前数量 |
| --- | ---: |
| `kb_qa_pairs` | `444` |
| `kb_qa_pairs_v2` | `444` |

## 3. 临时写入文档

测试文档 ID：

- `qa_alias_write_test_20260703_001`

核心字段：

| 字段 | 值 |
| --- | --- |
| `question` | `QA 写别名临时验证问题` |
| `answer_content` | `QA 写别名临时验证答案，验证后立即删除。` |
| `source_index` | `kb_document_official` |
| `acl_tokens` | `["_INTERNAL"]` |
| `visible_unit_codes` | `["global"]` |
| `permission_version` | `1` |
| `is_latest` | `true` |

第一次写入使用 1024 维零向量，被 ES 拒绝：

```text
The [cosine] similarity does not support vectors with zero magnitude.
```

原因：

- `question_vector` 使用 cosine similarity。
- cosine 不支持零模长向量。

修正：

- 使用首维为 `1.0`、其余维度为 `0.0` 的 1024 维非零向量。

修正后写入结果：

| 字段 | 值 |
| --- | --- |
| `_index` | `kb_qa_pairs_v2` |
| `_id` | `qa_alias_write_test_20260703_001` |
| `result` | `created` |

## 4. 写入后验证

| 检查项 | 结果 |
| --- | --- |
| `kb_qa_pairs` 数量 | `444` |
| `kb_qa_pairs_v2` 数量 | `445` |
| 旧索引是否存在测试文档 | 否 |
| v2 索引是否存在测试文档 | 是 |
| `kb_qa_read` 是否可读到测试文档 | 是 |
| `kb_qa_write` 是否可读到测试文档 | 是 |
| 测试文档实际 `_index` | `kb_qa_pairs_v2` |
| 测试文档 `acl_tokens` | `["_INTERNAL"]` |
| 测试文档 `source_index` | `kb_document_official` |

结论：

- `kb_qa_write` 写入实际落到了 `kb_qa_pairs_v2`。
- 旧索引 `kb_qa_pairs` 没有新增写入。

## 5. 清理结果

通过 `kb_qa_write` 删除测试文档：

| 字段 | 值 |
| --- | --- |
| `_index` | `kb_qa_pairs_v2` |
| `_id` | `qa_alias_write_test_20260703_001` |
| `result` | `deleted` |

清理后状态：

| 检查项 | 结果 |
| --- | --- |
| `kb_qa_pairs` 数量 | `444` |
| `kb_qa_pairs_v2` 数量 | `444` |
| 旧索引是否存在测试文档 | 否 |
| v2 索引是否存在测试文档 | 否 |

结论：测试数据已清理干净，索引数量恢复到验证前状态。

## 6. 最终结论

本轮验证通过：

- `kb_qa_write` 当前正确指向 `kb_qa_pairs_v2`。
- 新增 QA 文档通过写 alias 实际写入 `kb_qa_pairs_v2`。
- 旧索引 `kb_qa_pairs` 不再接收新增写入。
- 通过写 alias 删除文档也正确路由到 `kb_qa_pairs_v2`。
- 测试文档已删除，数据状态恢复。

## 7. 后续注意事项

- 后续所有 QA 测试向量必须使用非零向量，不能使用全零向量。
- 旧索引观察期内应继续监控 `kb_qa_pairs` count，确认没有新增写入。
- 下一步建议验证真实 QA 生成链路是否通过 `kb_qa_write` 写入 v2，并继承主文档权限字段。
