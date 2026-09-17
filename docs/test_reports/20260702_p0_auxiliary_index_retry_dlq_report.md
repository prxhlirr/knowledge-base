# 2026-07-02 P0 辅助索引补偿 DLQ 测试报告

## 一、实施范围

本轮在上一轮 `AUXILIARY_INDEX_RETRY` 自动补偿基础上，增加最大重试次数和死信队列，避免异常 source 无限重排。

## 二、关键变更

### 1. 入队 payload 增加 attempt

文件：

```text
ai_service/core/rag_pipeline.py
```

新增配置：

```text
AUXILIARY_INDEX_RETRY_MAX_ATTEMPTS
```

默认值：

```text
8
```

辅助索引同步失败入队时新增：

```json
{
  "attempt": 0
}
```

### 2. Worker 失败治理

文件：

```text
ai_service/task_worker.py
```

补偿失败后：

1. `attempt + 1`
2. 未达到上限：重新写回 `AUXILIARY_INDEX_RETRY`
3. 达到上限：写入 `AUXILIARY_INDEX_RETRY_DLQ`

## 三、测试命令与结果

### 1. 辅助索引补偿队列测试

命令：

```powershell
python ai_service\tools_and_tests\test_auxiliary_index_retry.py
```

结果：

```text
PASS auxiliary index retry tests
```

覆盖点：

1. 成功修复后，任务从 retry zset 移除。
2. 修复失败但未达上限时，任务重新入队，`attempt=1`。
3. 修复失败且达到最大次数时，任务进入 `AUXILIARY_INDEX_RETRY_DLQ`。

### 2. 辅助索引补齐脚本回归测试

命令：

```powershell
python ai_service\tools_and_tests\test_backfill_auxiliary_permission_projection.py
```

结果：

```text
PASS test_missing_projection_query_targets_source_and_unit_fields
PASS test_resolve_by_source_uses_chunk_physical_index_and_projection_fields
PASS test_build_update_doc_keeps_minimal_permission_projection
PASS test_process_batch_execute_updates_original_aux_physical_index
PASS test_source_projection_query_targets_one_document_source
PASS test_backfill_source_scans_each_aux_index_and_executes_updates
```

### 3. Java 回调补偿回归测试

命令：

```powershell
python ai_service\tools_and_tests\test_java_callback_retry.py
```

结果：

```text
PASS java callback retry tests
```

## 四、结论

1. 辅助索引补偿现在具备完整失败治理：成功移除、失败重排、超限 DLQ。
2. 单个坏 source 不会无限占用补偿队列。
3. 本轮未连接真实 Redis/ES，已完成本地可重复单元级验证。

## 五、运行建议

生产环境建议显式配置：

```text
AUXILIARY_INDEX_RETRY_MAX_ATTEMPTS=8
AUXILIARY_INDEX_RETRY_DELAY_SECONDS=60
AUXILIARY_RETRY_INDEXES=kb_doc_search,kb_doc_meta
```

同时监控：

```text
AUXILIARY_INDEX_RETRY
AUXILIARY_INDEX_RETRY_DLQ
```
