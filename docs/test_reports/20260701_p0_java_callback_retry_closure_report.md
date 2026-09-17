# P0 Java 回调补偿闭环测试报告

## 一、任务目标

本任务解决文档入库后 Java 回调失败只打印日志的问题。

第一性原理判断：

- ES 主索引写入成功只代表“检索投影存在”，不代表“权限权威闭环完成”。
- `kb_doc_registry`、权限事件写入 Java 失败时，如果没有持久化补偿，会产生 ES 与数据库权威记录分裂。
- 因此失败回调必须进入可恢复队列，而不是仅依赖后台线程日志。

## 二、代码变更

### 1. Java 回调失败进入 Redis 延迟补偿队列

文件：`ai_service/core/rag_pipeline.py`

新增能力：

- `enqueue_java_callback_retry(...)`
- `drain_due_java_callback_retries(...)`
- `_enqueue_java_callback_retry_from_env(...)`

补偿队列：

```text
JAVA_CALLBACK_RETRY
```

死信队列：

```text
JAVA_CALLBACK_RETRY_DLQ
```

可配置项：

```text
JAVA_CALLBACK_RETRY_QUEUE
JAVA_CALLBACK_RETRY_DELAY_SECONDS
JAVA_CALLBACK_RETRY_MAX_ATTEMPTS
```

覆盖场景：

- `/api/doc/perm/record` 权限事件回调非 2xx 或异常。
- `/api/v1/internal/doc/registry` registry 回调非 2xx 或异常。

### 2. Worker 主循环消费到期补偿任务

文件：`ai_service/task_worker.py`

变更点：

- 主文档 Worker 每轮处理任务前调用 `drain_due_java_callback_retries(redis_client)`。
- 复用现有 Redis 连接，不额外引入后台常驻线程。

设计原因：

- 当前 Worker 已经是入库链路的 Redis 消费者，顺手消费到期补偿任务改动最小。
- 失败补偿不会阻断文档任务主流程，符合最终一致性模型。

## 三、测试用例

新增文件：`ai_service/tools_and_tests/test_java_callback_retry.py`

覆盖用例：

1. `test_java_callback_retry_drains_successfully`
   - Java 回调返回 200 时，补偿任务从 sorted set 删除。

2. `test_java_callback_retry_requeues_failed_attempt`
   - Java 回调返回 503 时，任务重新入队，`attempt` 增加。

3. `test_java_callback_retry_moves_to_dlq_after_max_attempts`
   - 达到最大重试次数后，任务进入 `JAVA_CALLBACK_RETRY_DLQ`。

## 四、执行命令

```powershell
python -m py_compile ai_service\core\rag_pipeline.py ai_service\task_worker.py ai_service\tools_and_tests\test_java_callback_retry.py
python ai_service\tools_and_tests\test_java_callback_retry.py
python ai_service\tools_and_tests\test_qa_worker_delayed_retry.py
```

## 五、测试结果

```text
py_compile: PASS
PASS java callback retry tests
PASS qa worker delayed retry tests
```

执行时出现环境已有告警：

```text
RequestsDependencyWarning: urllib3/chardet/charset_normalizer version mismatch
```

该告警来自当前 Python 环境的 `requests` 依赖版本组合，不影响本次断言结果。

## 六、剩余边界

1. 当前补偿任务保存在 Redis，仍属于最终一致补偿，不是数据库强事务。
2. 如果 Redis 本身不可用，`_enqueue_java_callback_retry_from_env()` 会打印失败日志，但无法持久化。
3. 下一步应评估是否把 Java 回调补偿升级为数据库 Outbox 或 Redis + 定期巡检双保险。
4. `kb_doc_meta / kb_doc_search / QA` 辅助索引失败补偿仍需继续收口。
