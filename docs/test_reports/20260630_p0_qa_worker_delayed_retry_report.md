# P0 QA Worker 延迟重试测试报告

## 结论

已将 QA Worker 失败重试从“立即 lpush 回 QUEUE_QA”改为“写入 QUEUE_QA_RETRY sorted set，等待到期后再搬回 QUEUE_QA”。

覆盖范围：

- 独立 QA Worker：`ai_service/task_worker_qa.py`
- 主 Worker 内嵌 QA 线程：`ai_service/task_worker.py`

## 第一性原理校验

QA 生成依赖 LLM、ES、向量模型，失败通常来自外部服务短暂不可用。

如果失败后立即写回 `QUEUE_QA`，当前 Worker 会马上再次消费同一任务，形成热循环，快速打满 `MAX_RETRY` 或压垮 LLM/ES。

正确调度应把“等待”和“执行”分离：

1. 失败任务进入延迟集合。
2. 到期后搬回正式队列。
3. 正式队列仍保持单一消费入口。

## 代码变更

- `ai_service/task_worker_qa.py`
  - 新增 `QUEUE_QA_RETRY`。
  - 新增 `QA_TASK_RETRY_DELAY_SECONDS` 环境变量。
  - 新增 `enqueue_qa_retry_payload`。
  - 新增 `drain_due_qa_retry_payloads`。
  - 失败未达最大重试时写入延迟集合，不再立即写回 `QUEUE_QA`。
- `ai_service/task_worker.py`
  - 内嵌 QA Worker 使用相同 `QUEUE_QA_RETRY` 延迟重试语义。
- `ai_service/tools_and_tests/test_qa_worker_delayed_retry.py`
  - 使用 FakeRedis 验证延迟未到期不搬运、到期后搬回 `QUEUE_QA`。

## 已执行测试

```powershell
python -m py_compile ai_service\task_worker.py ai_service\task_worker_qa.py ai_service\tools_and_tests\test_qa_worker_delayed_retry.py
```

结果：通过。

```powershell
python ai_service\tools_and_tests\test_qa_worker_delayed_retry.py
```

结果：

```text
PASS qa worker delayed retry tests
```

## 环境提示

测试输出中出现 `requests` 依赖版本 warning：

```text
RequestsDependencyWarning
```

该 warning 来自当前 Python 环境依赖版本组合，不影响本次队列调度测试结果。

## 新增运行参数

```text
QA_TASK_RETRY_DELAY_SECONDS=60
```

默认 60 秒。
