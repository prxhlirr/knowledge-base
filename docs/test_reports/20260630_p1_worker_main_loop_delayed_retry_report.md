# P1 Worker 主循环延迟重试冒烟测试报告

## 结论

本轮从第一性原理复查 Worker 失败重试链路时发现：如果失败任务直接写回 `DOC_TASK_QUEUE`，而主循环又同时监听 `DOC_TASK_QUEUE_HIGH` 与 `DOC_TASK_QUEUE`，同一个失败任务会被当前 Worker 立刻再次消费，快速打满 `MAX_RETRY` 后进入 `DOC_TASK_DLQ`。这不是权限字段问题，而是失败调度语义缺陷。

已完成修复：失败任务未达到最大重试次数时写入 Redis 延迟集合 `DOC_TASK_RETRY`，到期后由主循环搬回 `DOC_TASK_QUEUE`。这样保留原有正式队列消费模型，同时避免失败任务热循环。

## 代码变更范围

- `ai_service/task_worker.py`
  - 新增 `QUEUE_RETRY=DOC_TASK_RETRY`。
  - 新增 `DOC_TASK_RETRY_DELAY_SECONDS` 环境变量，默认 60 秒。
  - 新增 `enqueue_retry_payload`，失败任务写入 Redis sorted set。
  - 新增 `drain_due_retry_payloads`，主循环搬运到期重试任务到低优先队列。
  - `process_payload_with_retry` 未达最大次数时改为进入延迟重试集合。
  - 主循环每轮 `brpop` 前先执行到期重试搬运。
- `ai_service/tools_and_tests/test_worker_failure_retry_e2e.py`
  - 断言失败任务进入 `DOC_TASK_RETRY`，不再即时进入 `DOC_TASK_QUEUE`。
  - 新增到期任务搬运回低优先队列的测试。
- `ai_service/tools_and_tests/test_worker_main_loop_retry_smoke.py`
  - 启动真实 `task_worker.py` 子进程。
  - 投递缺文件任务。
  - 验证主循环把任务写入 `DOC_TASK_RETRY`，且没有立即进入低优先队列或 DLQ。

## 已执行命令

```powershell
python -m py_compile ai_service\task_worker.py ai_service\tools_and_tests\test_worker_failure_retry_e2e.py ai_service\tools_and_tests\test_worker_main_loop_retry_smoke.py
```

结果：通过。

```powershell
python ai_service\tools_and_tests\test_worker_failure_retry_e2e.py
```

结果：通过，`Ran 3 tests in 1.308s OK`。

```powershell
python ai_service\tools_and_tests\test_worker_main_loop_retry_smoke.py
```

结果：通过，`Ran 1 test in 4.723s OK`。

```powershell
python ai_service\tools_and_tests\test_worker_queue_permission_e2e.py
```

结果：通过，`Ran 1 test in 7.381s OK`。

```powershell
python ai_service\tools_and_tests\test_task_worker_permission_projection.py
```

结果：通过，`Ran 4 tests in 0.000s OK`。

## 全流程测试明细

### 单次失败编排

输入：不存在的 `LOCAL_FS` 文件路径，`retryCount=0`。

流程：`DOC_TASK_QUEUE_HIGH` 入队 -> 单次消费 -> 文件可达性检查失败 -> 写入 `DOC_TASK_RETRY`。

断言：

- 返回结果为 `RETRY`。
- `DOC_TASK_QUEUE_HIGH` 为空。
- `DOC_TASK_QUEUE` 不新增即时重试任务。
- `DOC_TASK_RETRY` 新增 1 条任务。
- 任务 `retryCount=1`。
- `lastError` 包含 `Worker cannot access filePath`。

### 到期重试搬运

输入：`DOC_TASK_RETRY` 中插入一条 score 已过期的任务。

流程：调用 `drain_due_retry_payloads`。

断言：

- 返回搬运数量为 1。
- `DOC_TASK_RETRY` 被移除。
- `DOC_TASK_QUEUE` 新增 1 条任务。
- 任务 `taskId` 保持不变。

### 死信路径

输入：不存在的 `LOCAL_FS` 文件路径，`retryCount=MAX_RETRY`。

流程：`DOC_TASK_QUEUE_HIGH` 入队 -> 单次消费 -> 文件可达性检查失败 -> 写入 `DOC_TASK_DLQ`。

断言：

- 返回结果为 `DLQ`。
- `DOC_TASK_QUEUE_HIGH` 为空。
- `DOC_TASK_DLQ` 新增 1 条任务。
- 任务 `retryCount=MAX_RETRY`。

### 真实主循环冒烟

输入：真实启动 `task_worker.py` 子进程，投递缺文件任务，设置 `DOC_TASK_RETRY_DELAY_SECONDS=600`。

流程：Worker 初始化 Redis/RAGPipeline -> 主循环 `brpop` 消费高优先队列 -> 失败编排 -> 写入 `DOC_TASK_RETRY` -> 测试终止子进程。

断言：

- `DOC_TASK_RETRY` 出现当前测试任务。
- 任务 `retryCount=1`。
- `DOC_TASK_QUEUE_HIGH` 无当前测试任务残留。
- `DOC_TASK_QUEUE` 无当前测试任务残留。
- `DOC_TASK_DLQ` 无当前测试任务残留。

## 残留检查

本轮测试结束后检查结果：

```text
{'DOC_TASK_QUEUE_HIGH': 0, 'DOC_TASK_QUEUE': 0, 'DOC_TASK_DLQ': 0, 'DOC_TASK_RETRY': 0, 'QUEUE_QA': 0}
{'kb_document_law': 0, 'kb_doc_meta_v2': 0, 'kb_doc_search_v1': 0}
```

## 非阻塞观察

- `elasticsearch-py` 仍提示 `body` 参数弃用，这是现有客户端版本兼容警告，不影响本轮行为。
- Java 服务未在本地测试端口提供服务，相关回调按既有降级处理，不影响 Worker/Redis/ES 断言。

## 后续建议

下一步应继续验证“到期延迟任务在真实主循环中被搬回低优先队列并再次执行”的完整闭环。该测试需要把 `DOC_TASK_RETRY_DELAY_SECONDS` 设置为较小值，并确认任务不会因测试窗口过短产生误判。
