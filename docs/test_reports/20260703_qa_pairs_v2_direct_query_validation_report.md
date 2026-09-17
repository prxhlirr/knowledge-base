# kb_qa_pairs_v2 直查权限验证报告

## 1. 验证目标

本次验证用于确认 `kb_qa_pairs_v2` 在切换 `kb_qa_read/kb_qa_write` alias 之前，是否已经具备可上线的基础检索与权限过滤能力。

验证范围：

- 直接查询 `kb_qa_pairs_v2`，不经过 alias。
- 验证 KNN 向量检索是否能基于 `acl_tokens` 与 `source_index` 正确过滤。
- 验证 BM25 文本检索是否能基于 `acl_tokens` 与 `source_index` 正确过滤。
- 确认 `kb_qa_read/kb_qa_write` 尚未切换，避免影响线上读写链路。

## 2. 验证环境

- ES 地址：`http://localhost:9200`
- 验证索引：`kb_qa_pairs_v2`
- 当前读 alias：`kb_qa_read -> kb_qa_pairs`
- 当前写 alias：`kb_qa_write -> kb_qa_pairs`
- 验证时间：`2026-07-03`

## 3. 验证样例

从 `kb_qa_pairs_v2` 中抽取满足以下条件的样例：

- `acl_tokens = _INTERNAL`
- `source_index = kb_document_official`
- `question_vector` 存在

抽取结果：

| 字段 | 值 |
| --- | --- |
| `_id` | `ee200303f52275647138898037a80e0e_v0_qa_2_0` |
| `question` | `总书记什么时候强调的？` |
| `question_vector` 维度 | `1024` |

## 4. KNN 权限过滤验证

### 4.1 授权查询

过滤条件：

- `acl_tokens = _INTERNAL`
- `source_index = kb_document_official`

结果：

| 指标 | 值 |
| --- | --- |
| 返回数量 | `5` |
| ES total | `5` |
| 返回 `source_index` | `kb_document_official` |
| 返回 `acl_tokens` | `_INTERNAL` |
| 样例命中 ID | `ee200303f52275647138898037a80e0e_v0_qa_2_0`, `ee200303f52275647138898037a80e0e_v0_qa_2_1`, `ee200303f52275647138898037a80e0e_v0_qa_10_1` |

结论：KNN 查询在 `kb_qa_pairs_v2` 上可以命中授权范围内的数据，且返回结果没有越过 `source_index` 与 `acl_tokens` 过滤边界。

### 4.2 拒绝查询

过滤条件：

- `acl_tokens = __NO_SUCH_TOKEN__`

结果：

| 指标 | 值 |
| --- | --- |
| 返回数量 | `0` |

结论：不存在权限 token 时，KNN 查询返回 0，符合权限拒绝预期。

## 5. BM25 权限过滤验证

### 5.1 授权查询

查询文本：

- `总书记什么时候强调的？`

过滤条件：

- `acl_tokens = _INTERNAL`
- `source_index = kb_document_official`

结果：

| 指标 | 值 |
| --- | --- |
| 返回数量 | `5` |
| ES total | `308` |
| 返回 `source_index` | `kb_document_official` |
| 返回 `acl_tokens` | `_INTERNAL` |
| 样例命中 ID | `ee200303f52275647138898037a80e0e_v0_qa_2_0`, `ee200303f52275647138898037a80e0e_v0_qa_2_1`, `ee200303f52275647138898037a80e0e_v0_qa_11_1` |

结论：BM25 查询在 `kb_qa_pairs_v2` 上可以命中授权范围内的数据，且权限过滤生效。

### 5.2 拒绝查询

过滤条件：

- `acl_tokens = __NO_SUCH_TOKEN__`

结果：

| 指标 | 值 |
| --- | --- |
| 返回数量 | `0` |

结论：不存在权限 token 时，BM25 查询返回 0，符合权限拒绝预期。

## 6. Alias 状态验证

当前 alias 状态：

| alias | index | is_write_index |
| --- | --- | --- |
| `kb_qa_read` | `kb_qa_pairs` | `-` |
| `kb_qa_write` | `kb_qa_pairs` | `true` |

结论：本次验证没有切换 alias，线上读写入口仍然指向旧索引 `kb_qa_pairs`。

## 7. 总结

`kb_qa_pairs_v2` 已完成以下真实 ES 验证：

- KNN 授权检索通过。
- KNN 无权限拒绝通过。
- BM25 授权检索通过。
- BM25 无权限拒绝通过。
- alias 未切换，当前验证不会影响现有线上链路。

## 8. 下一步建议

下一步可以进入 alias 切换前的最终门禁：

- 确认 `kb_qa_pairs_v2` 全量数据量与旧索引一致。
- 确认 `question_vector`、`acl_tokens`、`source_index`、`visible_unit_codes`、`permission_version` 无缺失。
- 确认应用代码 QA 检索入口已兼容 `acl_tokens` keyword 字段。
- 在低峰期执行 `kb_qa_read/kb_qa_write` alias 切换。
- 切换后立即执行 KNN/BM25 正反向权限回归。
