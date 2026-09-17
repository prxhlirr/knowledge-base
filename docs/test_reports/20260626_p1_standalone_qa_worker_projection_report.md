# P1 任务测试报告：独立 QA Worker 透传 source_index 权限投影字段

## 1. 任务目标

补齐 `ai_service/task_worker_qa.py` 独立 QA Worker 的权限投影字段透传能力。

上一批已让 `QUEUE_QA` payload 携带 `targetIndex/ownerUnitCode/visibleUnitCodes/permissionVersion`，并让内嵌 QA Worker 透传这些字段。本批确保独立 QA Worker 消费同一队列时也能写出一致的 QA 权限字段。

## 2. 问题定位

文件：

- `ai_service/task_worker_qa.py`

原逻辑只读取并透传：

- `finChunks`
- `acl_tokens`
- `fileBaseHash`
- `docVersion`

缺失：

- `targetIndex`
- `ownerUnitCode`
- `visibleUnitCodes`
- `permissionVersion`

第一性原理结论：

- `QUEUE_QA` 可能由内嵌 Worker 或独立 Worker 消费。
- 如果两个消费者写入字段不一致，`kb_qa_pairs.source_index` 将取决于部署形态。
- QA 权限投影必须在所有消费者路径保持一致。

## 3. 本次修改范围

### `ai_service/task_worker_qa.py`

新增 payload 读取：

- `target_index = payload.get("targetIndex", "")`
- `owner_unit_code`
- `visible_unit_codes`
- `permission_version`

新增调用参数：

- `source_index=target_index`
- `ext_metadata=qa_meta`

## 4. 验证记录

### 4.1 Python 语法检查

执行命令：

```powershell
python -m py_compile ai_service/task_worker_qa.py
```

结果：

- 通过。

### 4.2 静态字段核对

执行命令：

```powershell
rg -n "targetIndex|ownerUnitCode|visibleUnitCodes|permissionVersion|source_index=target_index|ext_metadata=qa_meta|doc_version=doc_version" ai_service/task_worker_qa.py ai_service/task_worker.py
```

结果：

- 独立 QA Worker 与内嵌 QA Worker 均读取 `targetIndex`。
- 两者均传入 `source_index=target_index`。
- 两者均传入 `ext_metadata=qa_meta`。
- 两者均透传 `doc_version`。

## 5. 影响分析

### 5.1 正向影响

- 独立 QA Worker 和内嵌 QA Worker 写入字段一致。
- 新 QA 记录可以稳定写入 `source_index`，不再受部署形态影响。
- 为后续 QA 查询侧按可读索引范围过滤做好数据基础。

### 5.2 兼容性

- 旧队列消息缺失 `targetIndex` 时，`source_index` 为空字符串，不影响 QA 生成。
- 旧队列消息缺失单位字段时，单位投影为空，兼容现有逻辑。
- 不改变 Redis 消费、重试、死信队列流程。

## 6. 未完成项

本批仍未启用 QA 查询侧 `source_index` 过滤。

原因：

- 查询侧需要 Java 传 `resolvedIndexPattern`，Python KNN/BM25 两个端点都要加兼容过滤。
- 这是下一批独立任务，避免和 Worker 透传混在一起。

## 7. 结论

本任务已完成。

独立 QA Worker 已补齐 `targetIndex/docVersion/单位字段` 透传，Python 语法检查和静态核对均通过。
