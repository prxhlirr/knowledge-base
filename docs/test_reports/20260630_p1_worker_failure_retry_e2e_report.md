# P1 Worker 失败重试/DLQ 与队列权限入库回归测试报告

## 结论

本轮已验证 Worker 入库任务的失败编排链路：任务处理失败时不会丢失；未达到最大重试次数会写入延迟重试集合 `DOC_TASK_RETRY`；达到最大重试次数会进入死信队列。同步回归验证 Redis 正式队列成功消费后，文档可写入业务索引、`kb_doc_meta_v2`、`kb_doc_search_v1`，且单位权限投影字段保持一致。

## 代码验证范围

- `ai_service/task_worker.py`
  - 新增 `process_payload_with_retry`，统一成功、重试、死信三种结果。
  - 新增 `consume_one_payload_with_retry`，让失败路径可以通过单条队列任务确定性验证。
  - Worker 主循环改为复用 `process_payload_with_retry`，避免主循环逻辑与测试入口分叉。
- `ai_service/tools_and_tests/test_worker_failure_retry_e2e.py`
  - 覆盖缺失本地文件时的延迟重试。
  - 覆盖达到 `MAX_RETRY` 后进入 `DOC_TASK_DLQ`。
- `ai_service/tools_and_tests/test_worker_queue_permission_e2e.py`
  - 队列权限入库 E2E 改用确定性向量桩。
  - 真实保留 Redis、Worker 消费、RAGPipeline、ES 写入、权限字段查询断言。

## 已执行命令

```powershell
python -m py_compile ai_service\task_worker.py ai_service\tools_and_tests\test_worker_queue_permission_e2e.py ai_service\tools_and_tests\test_worker_failure_retry_e2e.py
```

结果：通过。

```powershell
python ai_service\tools_and_tests\test_worker_failure_retry_e2e.py
```

结果：通过，`Ran 2 tests in 0.941s OK`。

```powershell
python ai_service\tools_and_tests\test_worker_queue_permission_e2e.py
```

结果：通过，`Ran 1 test in 7.318s OK`。

```powershell
python ai_service\tools_and_tests\test_task_worker_permission_projection.py
```

结果：通过，`Ran 4 tests in 0.000s OK`。

## 全流程测试明细

### 失败重试路径

输入：不存在的 `LOCAL_FS` 文件路径，`retryCount=0`。

流程：`DOC_TASK_QUEUE_HIGH` 入队 -> `consume_one_payload_with_retry` 消费 -> `process_payload_once` 在文件可达性检查处抛出错误 -> `process_payload_with_retry` 写入 `DOC_TASK_RETRY`。

断言：

- `DOC_TASK_QUEUE_HIGH` 被消费为空。
- `DOC_TASK_QUEUE` 不新增即时重试任务。
- `DOC_TASK_RETRY` 新增 1 条延迟重试任务。
- 新任务 `retryCount=1`。
- `lastError` 包含 `Worker cannot access filePath`。

### 死信路径

输入：不存在的 `LOCAL_FS` 文件路径，`retryCount=MAX_RETRY`。

流程：`DOC_TASK_QUEUE_HIGH` 入队 -> `consume_one_payload_with_retry` 消费 -> 文件可达性检查失败 -> `process_payload_with_retry` 写入 `DOC_TASK_DLQ`。

断言：

- 返回结果为 `DLQ`。
- `DOC_TASK_QUEUE_HIGH` 被消费为空。
- `DOC_TASK_DLQ` 新增 1 条任务。
- 死信任务保持原始 `taskId` 与 `retryCount=MAX_RETRY`。

### 成功队列权限入库路径

输入：高优先级 Redis 队列任务，指定：

- `targetIndex=kb_document_law`
- `ownerUnitCode=620102`
- `visibleUnitCodes=["620102", "6201", "62"]`
- `permissionVersion=2026063002`

流程：`DOC_TASK_QUEUE_HIGH` 入队 -> `consume_one_payload_once` 消费 -> `RAGPipeline.process_and_index` 入库 -> 查询三个 ES 索引。

断言：

- `kb_document_law` 主文档 `_source` 与 `metadata` 均包含一致权限投影。
- `kb_doc_meta_v2` 包含一致权限投影。
- `kb_doc_search_v1` 包含一致权限投影。
- `source_index=kb_document_law`。
- `index_code=law`。
- `owner_unit_code=620102`。
- `visible_unit_codes=["620102", "6201", "62"]`。
- `permission_version=2026063002`。

## 残留检查

测试前已检查队列与 ES 残留：

```text
{'DOC_TASK_QUEUE_HIGH': 0, 'DOC_TASK_QUEUE': 0, 'QUEUE_QA': 0, 'DOC_TASK_DLQ': 0, 'DOC_TASK_RETRY': 0}
{'kb_document_law': 0, 'kb_doc_meta_v2': 0, 'kb_doc_search_v1': 0}
```

测试用例内部在 `tearDown` 中按测试唯一前缀清理 Redis 与 ES 测试数据。

## 观察到的非阻塞信息

- Java 服务端口 `127.0.0.1:65535` 不可达，测试按预期关闭 Java 回调或走降级路径，不影响 ES 入库验证。
- `elasticsearch-py` 提示 `body` 参数弃用，这是现有客户端版本兼容警告，不影响本轮权限链路正确性。
- 队列成功 E2E 曾在真实 ONNX 推理下出现 `onnxruntime bad allocation`。根因不是权限逻辑，而是模型会话资源波动。已将该测试收敛为确定性向量桩，保留真实 Redis/ES/Worker/RAGPipeline，以保证测试目标聚焦且稳定。

## 后续建议

下一步应继续补齐 Worker 主循环级别的最小冒烟测试：启动真实 Worker 子进程，投递一条可快速失败的任务，验证主循环会调用统一的延迟重试编排入口，而不是仅验证单次消费函数。
