# 2026-06-26 P2 QA 权限过滤端到端验证报告

## 任务范围

本批任务验证历史 QA `source_index` 全量回填后，QA 权限过滤能否按角色可读索引隔离结果。

验证对象：

- `kb_qa_read`
- `source_index`
- `source_index.keyword`
- `readable_source_indexes` 对应的严格过滤语义

本批不写 ES，只做只读查询验证。

## 数据基线

当前 QA 权限字段分布：

```text
kb_document_official=401
kb_document_public=18
```

基础严格过滤验证：

```text
official={'value': 401, 'relation': 'eq'}
public={'value': 18, 'relation': 'eq'}
official_public={'value': 419, 'relation': 'eq'}
law={'value': 0, 'relation': 'eq'}
```

结论：

- 只读 `official` 的角色只能看到 401 条。
- 只读 `public` 的角色只能看到 18 条。
- 同时可读 `official,public` 的角色可看到 419 条。
- 无相关权限的 `law` 角色看到 0 条。

## 样本隔离验证

### public 样本

测试文档：

- `source=test_gongshi.docx`
- 实际 `source_index=kb_document_public`

验证结果：

```text
source=test_gongshi.docx readable=['kb_document_public'] total={'value': 18, 'relation': 'eq'}
source=test_gongshi.docx readable=['kb_document_official'] total={'value': 0, 'relation': 'eq'}
```

结论：

- public 文档只在 public 权限下命中。
- official 权限不会串看到 public QA。

### official 样本

测试文档：

- `source=test.doc`
- 实际 `source_index=kb_document_official`

验证结果：

```text
source=test.doc readable=['kb_document_public'] total={'value': 0, 'relation': 'eq'}
source=test.doc readable=['kb_document_official'] total={'value': 50, 'relation': 'eq'}
```

结论：

- official 文档只在 official 权限下命中。
- public 权限不会串看到 official QA。

## 自动化测试

执行命令：

```bash
python ai_service/tools_and_tests/test_qa_source_index_filter.py
```

结果：

```text
PASS test_filter_uses_source_index_and_keyword_variant
PASS test_filter_ignores_alias_or_wildcard_to_avoid_guessing
PASS test_filter_ignores_non_document_indexes
```

结论：

- 查询 DSL 同时包含 `source_index` 和 `source_index.keyword`。
- 不接受 `kb_document` 读别名或通配符，避免 Python 侧猜测展开权限范围。
- 非 `kb_document_*` 索引不会生成 QA 权限过滤。

## AI HTTP 接口验证状态

计划验证：

- `/api/ai/qa/search/bm25`
- 请求中传入 `readable_source_indexes`
- 验证接口返回结果不跨越角色可读索引

实际状态：

```text
Test-NetConnection -ComputerName localhost -Port 8001
TcpTestSucceeded : False
```

尝试启动本地最小 QA 服务时，后台启动命令未被允许继续执行，因此未完成 HTTP 接口级验证。

本批已完成等价的 ES 查询过滤验证，但还未完成 FastAPI HTTP 层验证。

## 结论

ES 层 QA 权限过滤已验证通过：

- official 只命中 official QA；
- public 只命中 public QA；
- official+public 命中全部 QA；
- 无权限索引命中 0；
- 样本文档不存在跨索引串数。

HTTP 接口层仍需在 AI 服务可用后补测。

## 后续建议

在 AI 服务启动后执行 HTTP 接口补测：

1. 调用 `/api/ai/qa/search/bm25`，`readable_source_indexes=kb_document_public`。
2. 调用 `/api/ai/qa/search/bm25`，`readable_source_indexes=kb_document_official`。
3. 调用 `/api/ai/qa/search/bm25`，`readable_source_indexes=kb_document_official,kb_document_public`。
4. 校验返回结果中的 `source_index/index_code` 不越权。

若需要更稳定的回归测试，可新增一个不启动模型的 FastAPI 测试入口，直接 mock ES 响应验证请求体 DSL。
