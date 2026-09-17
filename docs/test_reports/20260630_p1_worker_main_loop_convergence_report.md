# P1 Worker 主循环成功路径收敛测试报告

## 1. 本轮目标

上一轮已经新增并验证了：

- `build_ext_meta_from_payload()`
- `process_payload_once()`
- `consume_one_payload_once()`

本轮目标是让 Worker 主循环的成功处理路径开始复用 `process_payload_once()`，避免生产主循环和 E2E 单任务入口在以下逻辑上继续分叉：

- `targetIndex` 透传；
- 单位权限字段投影；
- 本地文件/HTML 临时文件处理；
- `RAGPipeline.process_and_index()` 调用参数。

## 2. 本轮代码变更

### 2.1 `ai_service/task_worker.py`

主循环在从 Redis 队列取出 payload 后，新增执行路径：

```python
process_payload_once(payload, pipeline, notify_java=True)
```

保留原主循环外层职责：

- Redis 断线重连；
- 失败重试；
- 超过最大重试后进入 `DOC_TASK_DLQ`；
- 失败时回调 Java 和写操作日志。

设计原因：

- 入库成功路径和 E2E 单任务入口使用同一套字段构造；
- 避免 `targetIndex`、`visible_unit_codes` 等关键字段只在测试入口正确、生产入口遗漏；
- 保持失败编排暂不大改，降低本轮风险。

## 3. 测试结果

### 3.1 语法编译

命令：

```powershell
python -m py_compile ai_service/task_worker.py
```

结果：

- 通过。

### 3.2 模块导入

命令：

```powershell
python -c "import sys; sys.path.insert(0, 'ai_service'); import task_worker; print('import_ok')"
```

结果：

```text
import_ok
```

### 3.3 Redis 队列消费 E2E 回归

命令：

```powershell
python ai_service/tools_and_tests/test_worker_queue_permission_e2e.py
```

结果：

```text
Ran 1 test in 64.819s
OK
```

实际链路：

- `DOC_TASK_QUEUE_HIGH` 入队；
- 单条消费；
- 本地文件直读；
- 文档解析；
- 双粒度切片；
- ONNX CPU 向量化；
- `kb_document_law_write` 写入 5 条；
- `kb_doc_meta_write` 同步；
- `kb_doc_search_write` 同步；
- 三个索引权限字段校验通过。

### 3.4 残留清理验证

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

## 4. 当前状态说明

主循环的运行路径已经切到 `process_payload_once()`。

为了降低本轮风险，旧成功路径代码尚未整体删除；当前通过 `continue` 保证运行时不会进入旧代码。该旧代码不影响运行结果，但属于维护噪音。

下一步建议单独清理：

- 删除 `continue` 后不可达旧成功路径；
- 保留失败重试和 DLQ 编排；
- 再跑 `py_compile`、权限投影单测、队列 E2E。

## 5. 结论

本轮任务已完成。

结论：

- Worker 主循环成功路径已开始复用 `process_payload_once()`；
- 入库字段构造逻辑进一步收敛；
- 队列消费 E2E 回归通过；
- Redis 和 ES 测试残留已清理。

