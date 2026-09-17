# kb_qa_pairs_v2 Alias 切换与回归测试报告

## 1. 执行目标

将 QA 检索读写入口从旧物理索引 `kb_qa_pairs` 原子切换到新物理索引 `kb_qa_pairs_v2`。

本次切换对象：

- `kb_qa_read`
- `kb_qa_write`

旧物理索引 `kb_qa_pairs` 保留，不删除，用于异常时回滚。

## 2. 切换前预检

| 检查项 | 结果 |
| --- | --- |
| `kb_qa_pairs` 文档数 | `444` |
| `kb_qa_pairs_v2` 文档数 | `444` |
| 新旧文档数是否一致 | 通过 |
| `question` 缺失数 | `0` |
| `question_vector` 缺失数 | `0` |
| `acl_tokens` 缺失数 | `0` |
| `source_index` 缺失数 | `0` |
| `visible_unit_codes` 缺失数 | `0` |
| `permission_version` 缺失数 | `0` |

切换前 alias 状态：

| alias | index | is_write_index |
| --- | --- | --- |
| `kb_qa_read` | `kb_qa_pairs` | `-` |
| `kb_qa_write` | `kb_qa_pairs` | `true` |

结论：切换前门禁通过。

## 3. 切换执行

第一次尝试失败：

- 原因：当前 ES 版本的 alias `remove` action 不支持 `ignore_unavailable` 字段。
- ES 返回：`[remove] unknown field [ignore_unavailable]`
- 影响：该请求解析失败，没有执行 alias 变更。

修正后执行原子 alias 更新：

```json
{
  "actions": [
    {"remove": {"index": "kb_qa_pairs", "alias": "kb_qa_read"}},
    {"remove": {"index": "kb_qa_pairs", "alias": "kb_qa_write"}},
    {"add": {"index": "kb_qa_pairs_v2", "alias": "kb_qa_read"}},
    {"add": {"index": "kb_qa_pairs_v2", "alias": "kb_qa_write", "is_write_index": true}}
  ]
}
```

ES 返回：

```json
{"acknowledged": true}
```

## 4. 切换后 Alias 状态

| alias | index | is_write_index |
| --- | --- | --- |
| `kb_qa_read` | `kb_qa_pairs_v2` | `-` |
| `kb_qa_write` | `kb_qa_pairs_v2` | `true` |

结论：读写 alias 均已指向 `kb_qa_pairs_v2`。

## 5. 切换后权限回归

回归查询均通过 `kb_qa_read` alias 执行，验证应用真实读入口。

抽样数据：

| 字段 | 值 |
| --- | --- |
| `_id` | `ee200303f52275647138898037a80e0e_v0_qa_2_0` |
| `question` | `总书记什么时候强调的？` |
| `question_vector` 维度 | `1024` |

### 5.1 KNN 授权查询

过滤条件：

- `acl_tokens = _INTERNAL`
- `source_index = kb_document_official`

结果：

| 指标 | 值 |
| --- | --- |
| 返回数量 | `5` |
| ES total | `5` |
| 命中物理索引 | `kb_qa_pairs_v2` |
| 返回 `source_index` | `kb_document_official` |
| 返回 `acl_tokens` | `_INTERNAL` |

结论：KNN 授权检索通过。

### 5.2 KNN 无权限查询

过滤条件：

- `acl_tokens = __NO_SUCH_TOKEN__`

结果：

| 指标 | 值 |
| --- | --- |
| 返回数量 | `0` |

结论：KNN 无权限拒绝通过。

### 5.3 BM25 授权查询

过滤条件：

- `acl_tokens = _INTERNAL`
- `source_index = kb_document_official`

结果：

| 指标 | 值 |
| --- | --- |
| 返回数量 | `5` |
| ES total | `308` |
| 命中物理索引 | `kb_qa_pairs_v2` |
| 返回 `source_index` | `kb_document_official` |
| 返回 `acl_tokens` | `_INTERNAL` |

结论：BM25 授权检索通过。

### 5.4 BM25 无权限查询

过滤条件：

- `acl_tokens = __NO_SUCH_TOKEN__`

结果：

| 指标 | 值 |
| --- | --- |
| 返回数量 | `0` |

结论：BM25 无权限拒绝通过。

## 6. 本次结论

本次 alias 切换成功：

- `kb_qa_read` 已从 `kb_qa_pairs` 切换到 `kb_qa_pairs_v2`。
- `kb_qa_write` 已从 `kb_qa_pairs` 切换到 `kb_qa_pairs_v2`，并保持 `is_write_index=true`。
- 切换后 KNN/BM25 正反向权限回归通过。
- 旧索引 `kb_qa_pairs` 已保留，可作为回滚目标。

## 7. 回滚命令

如果切换后出现异常，可执行以下 alias 原子回滚：

```json
{
  "actions": [
    {"remove": {"index": "kb_qa_pairs_v2", "alias": "kb_qa_read"}},
    {"remove": {"index": "kb_qa_pairs_v2", "alias": "kb_qa_write"}},
    {"add": {"index": "kb_qa_pairs", "alias": "kb_qa_read"}},
    {"add": {"index": "kb_qa_pairs", "alias": "kb_qa_write", "is_write_index": true}}
  ]
}
```

回滚后必须重新执行：

- alias 状态验证。
- KNN 授权与无权限查询。
- BM25 授权与无权限查询。

## 8. 后续边缘测试建议

建议继续覆盖以下场景：

- 通过应用 HTTP API 调用 `/api/ai/qa/search`，确认服务层请求经过 `kb_qa_read` 后仍返回 v2 数据。
- 通过应用 HTTP API 调用 `/api/ai/qa/search/bm25`，确认 BM25 路径在服务层无回归。
- 新增一条 QA 写入样例，确认 `kb_qa_write` 写入目标为 `kb_qa_pairs_v2`。
- 使用普通部门 token、管理员 token、空 token 分别验证 fail-open/fail-closed 边界。
- 验证 Java 侧传入 `readable_source_indexes` 时，QA 聚合索引结果不会越过角色可读文档索引范围。
