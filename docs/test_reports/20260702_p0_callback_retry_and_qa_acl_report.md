# 2026-07-02 P0 Java 回调补偿与 QA ACL 默认拒绝测试报告

## 一、实施范围

本轮继续处理文档入库后的权限闭环一致性，重点确认并加固两类路径：

1. ES 入库成功后，Java registry/权限事件回调失败时必须进入可恢复补偿队列。
2. QA 索引入库路径缺失 ACL 时必须默认拒绝，不能默认 `_INTERNAL`。

## 二、代码确认结果

### 1. Java 回调补偿链路

已确认以下链路存在并接入 worker 主循环：

1. `ai_service/core/rag_pipeline.py`
   - `enqueue_java_callback_retry`
   - `drain_due_java_callback_retries`
   - `_enqueue_java_callback_retry_from_env`
2. `ai_service/task_worker.py`
   - 主循环每轮先执行 `drain_due_java_callback_retries(redis_client)`。

结论：

```text
回调失败不是只打日志，已经可以进入 Redis sorted set 延迟补偿队列，并由 worker 主循环持续消费。
```

### 2. QA ACL 默认值

本轮收紧以下文件：

1. `ai_service/task_worker.py`
   - 新增 `DEFAULT_QA_ACL_TOKENS = ["_NO_ACCESS"]`
   - 内嵌 QA worker 缺失 `acl_tokens` 时使用该常量。
2. `ai_service/task_worker_qa.py`
   - 新增 `DEFAULT_QA_ACL_TOKENS = ["_NO_ACCESS"]`
   - 独立 QA worker 缺失 `acl_tokens` 时使用该常量。

优化原因：

```text
QA 索引同样参与检索召回。缺失 ACL 时如果默认 _INTERNAL，会绕开上一轮已经建立的 fail-closed 原则。
```

## 三、测试命令与结果

### 1. Java 回调补偿测试

命令：

```powershell
python ai_service\tools_and_tests\test_java_callback_retry.py
```

结果：

```text
PASS java callback retry tests
```

覆盖点：

1. 到期补偿任务 HTTP 成功后，从 Redis retry zset 移除。
2. HTTP 失败时，任务重新入队且 `attempt + 1`。
3. 达到最大重试次数后，任务进入 `JAVA_CALLBACK_RETRY_DLQ`。

### 2. QA worker 延迟重试与 ACL 默认值测试

命令：

```powershell
python ai_service\tools_and_tests\test_qa_worker_delayed_retry.py
```

结果：

```text
PASS qa worker delayed retry tests
```

覆盖点：

1. 独立 QA worker 延迟重试未到期不会提前搬回正式队列。
2. 内嵌 QA worker 延迟重试未到期不会提前搬回正式队列。
3. 独立 QA worker 与内嵌 QA worker 的缺失 ACL 默认值均为 `_NO_ACCESS`。

### 3. ACL payload 策略回归测试

命令：

```powershell
python ai_service\tools_and_tests\test_acl_payload_policy.py
```

结果：

```text
PASS acl payload policy tests
```

覆盖点：

1. 缺失 ACL 默认 `_NO_ACCESS`。
2. 显式兼容策略 `internal` 仍可返回 `_INTERNAL`。
3. 非法 ACL JSON 遵循 `no_access` 策略。

## 四、测试备注

执行 Python 测试时出现本地依赖提示：

```text
RequestsDependencyWarning: urllib3/chardet/charset_normalizer version mismatch
```

该提示来自本地 Python requests 依赖版本组合，不影响本轮测试断言结果。

## 五、结论

1. Java registry/权限事件回调失败已具备延迟补偿和 DLQ 兜底。
2. QA 入库路径已经与主文档入库路径保持一致：缺失 ACL 默认不可访问。
3. 本轮未执行真实 Redis/Java HTTP 联调，只完成了可重复的本地单元级验证。
