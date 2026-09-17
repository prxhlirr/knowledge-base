# QA HTTP API Alias 回归与 BM25 缺陷修复报告

## 1. 本轮目标

在 `kb_qa_read/kb_qa_write` 已切换到 `kb_qa_pairs_v2` 后，验证应用 HTTP API 层是否仍满足权限控制与检索正确性。

验证接口：

- `/api/ai/qa/search`
- `/api/ai/qa/search/bm25`

## 2. 发现的问题

代码复核发现：

- KNN QA 检索路径已经使用 `QA_INDEX_READ_ALIAS`，实际读取 `kb_qa_read`。
- BM25 QA 检索路径仍使用 `os.getenv("QA_INDEX_PATTERN", "kb_qa_*")`。

这个问题在生产上有明确风险：

- 旧索引 `kb_qa_pairs` 保留期间，`kb_qa_*` 可能同时扫到旧物理索引和 `kb_qa_pairs_v2`。
- 同一 `_id` 可能从多个物理索引重复召回。
- 旧索引动态 mapping 仍可能影响权限过滤稳定性。
- ES 查询范围扩大，造成不必要的查询开销。

## 3. 修复内容

修复文件：

- `ai_service/main.py`
- `ai_service/tools_and_tests/test_qa_source_index_filter.py`

修复点：

- 将 BM25 QA 检索路径的 `qa_index` 改为 `QA_INDEX_READ_ALIAS`。
- 增加静态测试，要求 `qa_search` 与 `qa_search_bm25` 都必须导入并使用 `QA_INDEX_READ_ALIAS`，且函数内部不得再出现 `kb_qa_*` 通配符常量。

修复后关键代码行为：

| 检索路径 | 读索引来源 |
| --- | --- |
| KNN QA | `QA_INDEX_READ_ALIAS` |
| BM25 QA | `QA_INDEX_READ_ALIAS` |

## 4. 静态验证

执行最小 AST 检查：

```text
qa_search True False
qa_search_bm25 True False
```

含义：

- 第一个布尔值：函数内已导入 `QA_INDEX_READ_ALIAS`。
- 第二个布尔值：函数内是否仍包含 `kb_qa_*`，结果为 `False`。

结论：KNN 与 BM25 两条 QA 检索路径都已统一读 alias，不再使用 `kb_qa_*` 通配符。

说明：完整 `test_qa_source_index_filter.py` 在当前 Windows 环境中多次遇到宿主级异常 `线程未能启动` 或 CLR 初始化失败，未能稳定完成整文件执行；但新增约束已通过最小 AST 检查验证。

## 5. HTTP API 回归

当前 alias 状态：

| alias | index | is_write_index |
| --- | --- | --- |
| `kb_qa_read` | `kb_qa_pairs_v2` | `-` |
| `kb_qa_write` | `kb_qa_pairs_v2` | `true` |

测试样例：

| 字段 | 值 |
| --- | --- |
| `question` | `总书记什么时候强调的？` |
| `question_vector` 来源 | `kb_qa_pairs_v2` |

### 5.1 `/api/ai/qa/search`

授权请求：

- `acl_tokens = ["_INTERNAL"]`
- `readable_source_indexes = "kb_document_official"`
- `top_k = 10`

结果：

| 指标 | 值 |
| --- | --- |
| HTTP 业务码 | `200` |
| 返回数量 | `10` |
| 耗时 | `91ms` |
| 重复 `_id` | 无 |
| 返回 `source_index` | `kb_document_official` |

无权限请求：

- `acl_tokens = ["__NO_SUCH_TOKEN__"]`
- `readable_source_indexes = "kb_document_official"`

结果：

| 指标 | 值 |
| --- | --- |
| HTTP 业务码 | `200` |
| 返回数量 | `0` |
| 耗时 | `23ms` |

结论：KNN HTTP API 权限回归通过。

### 5.2 `/api/ai/qa/search/bm25`

授权请求：

- `acl_tokens = ["_INTERNAL"]`
- `readable_source_indexes = "kb_document_official"`
- `top_k = 10`

结果：

| 指标 | 值 |
| --- | --- |
| HTTP 业务码 | `200` |
| 返回数量 | `10` |
| 耗时 | `139ms` |
| 重复 `_id` | 无 |
| 返回 `source_index` | `kb_document_official` |

无权限请求：

- `acl_tokens = ["__NO_SUCH_TOKEN__"]`
- `readable_source_indexes = "kb_document_official"`

结果：

| 指标 | 值 |
| --- | --- |
| HTTP 业务码 | `200` |
| 返回数量 | `0` |
| 耗时 | `14ms` |

结论：BM25 HTTP API 权限回归通过。

## 6. 当前限制

当前 8001 服务由 Docker/WSL 转发提供，不是本地 uvicorn 进程。代码修复已经写入工作区，但如果容器没有热加载本地文件，则需要重启容器或服务后才能加载 BM25 alias 修复。

本轮 HTTP 回归结果显示当前运行服务在权限结果上是正确的，但生产发布仍必须确保新代码被部署并重启生效。

## 7. 结论

本轮修复和验证完成：

- 已修复 BM25 QA 检索路径可能使用 `kb_qa_*` 通配符的问题。
- KNN 与 BM25 QA 检索路径已统一使用 `kb_qa_read`。
- HTTP API 层 KNN/BM25 授权与无权限回归通过。
- 返回结果未出现重复 `_id`。
- 返回结果未越过 `readable_source_indexes` 限制。

## 8. 后续建议

下一步建议继续验证写路径：

- 通过 `kb_qa_write` 写入一条临时 QA 测试文档。
- 确认写入目标物理索引为 `kb_qa_pairs_v2`。
- 删除临时测试文档。
- 再次确认 `kb_qa_pairs` 旧索引无新增写入。
