# P1 Worker 主循环不可达旧代码清理测试报告

## 1. 本轮目标

上一轮已经将 Worker 主循环成功路径切换到：

```python
process_payload_once(payload, pipeline, notify_java=True)
```

但为了降低风险，旧成功路径代码暂时保留在 `continue` 后，运行时不可达。

本轮目标：

- 删除 `continue` 后不可达旧成功路径；
- 保留 Redis 断线重连、失败重试、DLQ 编排；
- 确认队列 E2E 和权限字段写入不受影响。

## 2. 本轮代码变更

### 2.1 `ai_service/task_worker.py`

删除内容：

- 主循环中已经不可达的旧 payload 解析逻辑；
- 旧 `htmlContent` 临时文件处理；
- 旧 `ext_meta` 手工构造；
- 旧 `pipeline.process_and_index(...)` 调用；
- 旧成功后 Java 回调和操作日志写入分支。

保留内容：

- `process_payload_once()` 单任务入口；
- 主循环 Redis `brpop`；
- 失败重试进入 `DOC_TASK_QUEUE`；
- 超过最大重试进入 `DOC_TASK_DLQ`；
- Redis 断线重连；
- 系统级异常兜底。

清理后的主循环核心路径：

```python
payload = json.loads(payload_str)
process_payload_once(payload, pipeline, notify_java=True)
```

## 3. 测试结果

### 3.1 主循环结构检查

检查结果：

- 主循环中只保留一处 `process_payload_once(payload, pipeline, notify_java=True)`；
- 旧 `ext_meta = { ... }` 构造块已经移除；
- 旧主循环内直接调用 `pipeline.process_and_index(...)` 已移除；
- `build_ext_meta_from_payload()` 和 `process_payload_once()` 内仍保留必要实现。

### 3.2 语法编译

命令：

```powershell
python -m py_compile ai_service/task_worker.py
```

结果：

- 通过。

### 3.3 模块导入

命令：

```powershell
python -c "import sys; sys.path.insert(0, 'ai_service'); import task_worker; print('import_ok')"
```

结果：

```text
import_ok
```

### 3.4 权限投影回归

命令：

```powershell
python ai_service/tools_and_tests/test_task_worker_permission_projection.py
```

结果：

```text
Ran 4 tests in 0.000s
OK
```

### 3.5 模型延迟加载回归

命令：

```powershell
python ai_service/tools_and_tests/test_model_manager_lazy_runtime.py
```

结果：

```text
Ran 2 tests in 16.374s
OK
```

### 3.6 Redis 队列消费 E2E

命令：

```powershell
python ai_service/tools_and_tests/test_worker_queue_permission_e2e.py
```

结果：

```text
Ran 1 test in 61.742s
OK
```

实际验证：

- `DOC_TASK_QUEUE_HIGH` 入队；
- 单条消费；
- `targetIndex=kb_document_law` 生效；
- 主索引写入成功；
- `kb_doc_meta_v2` 同步成功；
- `kb_doc_search_v1` 同步成功；
- 三类索引权限字段断言通过。

## 4. 残留清理验证

Redis 队列：

```text
DOC_TASK_QUEUE_HIGH=0
DOC_TASK_QUEUE=0
QUEUE_QA=0
DOC_TASK_DLQ=0
```

ES 测试数据：

```text
kb_document_law=0
kb_doc_meta_v2=0
kb_doc_search_v1=0
```

## 5. 结论

本轮任务已完成。

结论：

- Worker 主循环不可达旧成功路径已清理；
- 主循环成功路径现在收敛到 `process_payload_once()`；
- 权限投影、队列消费、ES 入库 E2E 全部通过；
- 测试数据和队列残留已清理。

