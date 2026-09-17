# 真实 QA 生成链路写入与权限投影验证报告

## 1. 验证目标

验证真实 QA 生成链路是否满足以下生产要求：

- QA 生成后的 bulk 写入必须走 `kb_qa_write`。
- QA 文档必须继承主文档的权限投影字段。
- Redis 异步 QA 任务必须携带权限字段，worker 消费后不能丢失。
- 当前 `kb_qa_pairs_v2` 中权限字段保持完整。

## 2. 代码链路结论

真实 QA 生成入口：

- `RAGPipeline.process_and_index`
- `RAGPipeline._generate_and_index_qa_pairs`
- `task_worker_qa.py`
- `task_worker.py`

### 2.1 主流程入队

`RAGPipeline.process_and_index` 在生成 QA 任务时，payload 已携带：

| payload 字段 | 作用 |
| --- | --- |
| `targetIndex` | 原文档写入的 `kb_document_*` 物理索引 |
| `ownerUnitCode` | 文档所属单位 |
| `visibleUnitCodes` | 文档可见单位链 |
| `permissionVersion` | 权限版本 |
| `acl_tokens` | ES 前置权限 token |

### 2.2 Worker 消费

`task_worker_qa.py` 与 `task_worker.py` 均调用：

- `build_permission_projection_from_payload(payload)`

并将结果作为 `ext_metadata` 传入：

- `RAGPipeline._generate_and_index_qa_pairs`

结论：异步 worker 路径不会丢失单位权限字段。

### 2.3 QA bulk 写入

`RAGPipeline._generate_and_index_qa_pairs` 中 QA bulk action 使用：

- `_index = QA_INDEX_WRITE_ALIAS`

也就是写入 `kb_qa_write`，由 ES alias 决定当前活跃物理索引。

QA `_source` 包含：

| 字段 | 结论 |
| --- | --- |
| `source_index` | 已写入 |
| `index_code` | 已写入 |
| `owner_unit_code` | 已写入 |
| `visible_unit_codes` | 已写入 |
| `permission_version` | 已写入 |
| `acl_tokens` | 已写入 |
| `doc_version` | 已写入 |
| `is_latest` | 已写入 |

## 3. 新增测试

新增测试文件：

- `ai_service/tools_and_tests/test_qa_generation_write_projection.py`

覆盖内容：

| 测试项 | 结果 |
| --- | --- |
| `test_qa_bulk_write_uses_write_alias` | 通过 |
| `test_qa_bulk_write_projects_permission_fields` | 通过 |
| `test_qa_generation_uses_permission_projection_once` | 通过 |
| `test_qa_queue_payload_carries_permission_fields` | 通过 |

执行命令：

```powershell
python -X utf8 ai_service\tools_and_tests\test_qa_generation_write_projection.py
```

执行结果：

```text
PASS test_qa_bulk_write_uses_write_alias
PASS test_qa_bulk_write_projects_permission_fields
PASS test_qa_generation_uses_permission_projection_once
PASS test_qa_queue_payload_carries_permission_fields
```

## 4. 既有 Worker 投影测试

执行命令：

```powershell
python -X utf8 ai_service\tools_and_tests\test_task_worker_permission_projection.py
```

执行结果：

```text
Ran 4 tests in 0.000s
OK
```

覆盖内容：

- 优先使用 Java camelCase 权限字段。
- 支持中文逗号和英文逗号分隔的 `visibleUnitCodes` 字符串。
- 历史 payload 缺少单位字段时用 `deptCode` 兜底。
- 完全缺失单位字段时使用 `global` 兜底。

## 5. ES 现状抽样

当前 alias：

| alias | index | is_write_index |
| --- | --- | --- |
| `kb_qa_read` | `kb_qa_pairs_v2` | `-` |
| `kb_qa_write` | `kb_qa_pairs_v2` | `true` |

`kb_qa_pairs_v2` 关键字段缺失统计：

| 字段 | 缺失数量 |
| --- | ---: |
| `acl_tokens` | `0` |
| `source_index` | `0` |
| `index_code` | `0` |
| `owner_unit_code` | `0` |
| `visible_unit_codes` | `0` |
| `permission_version` | `0` |
| `doc_version` | `0` |
| `is_latest` | `0` |

字段分布：

| 字段 | 样例分布 |
| --- | --- |
| `source_index` | `kb_document_official=401`, `kb_document_news=25`, `kb_document_public=18` |
| `visible_unit_codes` | `global=419`, `62=25`, `6201=25`, `620102=25`, `62010290=25` |
| `acl_tokens` | `_INTERNAL=419`, `dept::62=25`, `dept::6201=25`, `dept::620102=25`, `dept::62010290=25`, `user::god-admin=25` |

## 6. 本轮结论

本轮验证通过：

- 真实 QA bulk 写入代码走 `kb_qa_write`，没有写死旧物理索引。
- QA 文档会写入权限控制需要的完整字段。
- Redis QA payload 会携带权限字段。
- QA worker 会把 payload 权限字段转换为 `_generate_and_index_qa_pairs` 可识别的 `ext_metadata`。
- 当前 `kb_qa_pairs_v2` 的权限字段缺失数为 0。

## 7. 仍需继续验证的边界

本轮属于代码合同与 ES 现状验证，下一步仍建议做一次真实新文档入库端到端验证：

1. 上传或模拟一篇带单位权限的新文档。
2. 触发真实 chunk 入库。
3. 等待 QA 任务消费。
4. 查询新文档派生的 QA 是否写入 `kb_qa_pairs_v2`。
5. 对比主文档 chunk 与 QA 的权限字段是否一致。
6. 使用授权 token 和无权限 token 分别检索，确认 QA 不越权。
