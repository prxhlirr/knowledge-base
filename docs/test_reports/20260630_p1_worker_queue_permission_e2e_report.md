# P1 Worker Redis 队列消费权限字段 E2E 测试报告

## 1. 本轮目标

在上一轮已经验证 `RAGPipeline.process_and_index()` 核心入库链路后，本轮继续验证 Redis 队列协议：

1. 任务真实进入 `DOC_TASK_QUEUE_HIGH`；
2. Worker 按生产优先级从高优先级队列取出 payload；
3. 使用 payload 中的权限字段和 `targetIndex` 入库；
4. 主索引、元索引、搜索索引均写入一致的权限字段；
5. 测试完成后自动清理 Redis 和 ES 残留。

## 2. 本轮发现

### 2.1 `targetIndex` 未透传到 `ext_metadata`

代码验证位置：

- `ai_service/task_worker.py`

问题：

- 原主循环构建 `ext_meta` 时没有设置 `targetIndex`；
- `RAGPipeline._resolve_target_index()` 的第一优先级是 `ext_metadata["targetIndex"]`；
- 如果 Worker 不透传该字段，文档入库时就不会优先使用 Java 已经决策好的目标索引，只能退回 Python 文档类型路由或 fallback 索引。

影响：

- 角色按索引配置可见范围时，文档可能写入错误索引；
- `source_index/index_code` 权限投影也会随之错误；
- 这正是“文档在入库时有字段控制进入哪个索引，但逻辑可能没正常使用”的根因之一。

## 3. 本轮代码变更

### 3.1 `ai_service/task_worker.py`

新增：

- `build_ext_meta_from_payload(payload)`
  - 将 Redis payload 转成 `RAGPipeline` 所需 `ext_metadata`；
  - 合并 `build_permission_projection_from_payload()` 生成的单位权限投影；
  - 透传 `targetIndex`。

- `process_payload_once(payload, pipeline, notify_java=True)`
  - 处理单条文档入库任务；
  - 支持本地文件和 `htmlContent` 临时文件；
  - 可关闭 Java 回调，便于 E2E 自动化测试。

- `consume_one_payload_once(redis_client, pipeline, timeout=5, notify_java=True)`
  - 按生产队列优先级 `DOC_TASK_QUEUE_HIGH -> DOC_TASK_QUEUE` 消费一条任务；
  - 消费后立即返回，避免无限循环无法收口。

修复：

- 新 helper 中透传 `targetIndex`；
- 现有主循环旧代码块也同步补充 `targetIndex`，避免生产路径继续丢失该字段。

### 3.2 `ai_service/tools_and_tests/test_worker_queue_permission_e2e.py`

新增真实队列 E2E：

- 写入临时 txt 文件；
- 构造完整 payload；
- `LPUSH DOC_TASK_QUEUE_HIGH`；
- 调用 `consume_one_payload_once()` 执行一次消费；
- 校验：
  - `kb_document_law`
  - `kb_doc_meta_v2`
  - `kb_doc_search_v1`
- 清理：
  - ES 测试文档；
  - `QUEUE_QA` 残留；
  - 正式队列残留。

## 4. 测试结果

### 4.1 编译检查

命令：

```powershell
python -m py_compile ai_service/task_worker.py ai_service/tools_and_tests/test_worker_queue_permission_e2e.py
```

结果：

- 通过。

### 4.2 权限投影回归

命令：

```powershell
python ai_service/tools_and_tests/test_task_worker_permission_projection.py
```

结果：

```text
Ran 4 tests in 0.000s
OK
```

### 4.3 模型延迟加载回归

命令：

```powershell
python ai_service/tools_and_tests/test_model_manager_lazy_runtime.py
```

结果：

```text
Ran 2 tests in 9.960s
OK
```

### 4.4 Redis 队列消费真实 E2E

命令：

```powershell
python ai_service/tools_and_tests/test_worker_queue_permission_e2e.py
```

结果：

```text
Ran 1 test in 17.899s
OK
```

实际链路：

- `DOC_TASK_QUEUE_HIGH` 入队成功；
- 单条任务消费成功；
- 本地文件直读；
- 文档解析成功；
- 双粒度切片：粗粒度 1 块，细粒度 4 块；
- ONNX CPU 向量化成功；
- `kb_document_law_write` bulk 写入 5 条成功；
- `kb_doc_meta_write` 同步成功；
- `kb_doc_search_write` 同步成功；
- 三个索引权限字段断言通过。

## 5. 清理验证

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

## 6. 剩余事项

### 6.1 主循环仍需收敛到 `process_payload_once`

本轮为了降低风险，没有一次性移动主循环内的大段异常处理代码。

当前状态：

- 新增的一次性入口已通过真实队列 E2E；
- 现有主循环已补充 `targetIndex` 和权限投影；
- 下一步可以把主循环成功路径收敛到 `process_payload_once()`，减少重复逻辑。

### 6.2 失败重试路径需要单独 E2E

本轮验证的是成功路径。

下一步建议补充：

- 文件不存在时重入 `DOC_TASK_QUEUE`；
- 超过 `MAX_RETRY` 后进入 `DOC_TASK_DLQ`；
- `notify_java=False` 测试模式下不产生外部 Java 副作用；
- `notify_java=True` 时回调失败是否按预期触发重试。

## 7. 结论

本轮任务已完成。

结论：

- 已发现并修复 `targetIndex` 未透传导致入库索引控制失效的缺陷；
- 已新增可收口的单任务消费入口；
- 已通过真实 Redis 队列 -> Worker 单次消费 -> ES 入库 -> 权限字段校验的端到端测试；
- 测试数据和队列残留已清理。

