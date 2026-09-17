# 2026-07-02 P0 辅助索引同步失败补偿测试报告

## 一、实施范围

本轮处理文档主索引写入成功后，`kb_doc_meta/kb_doc_search` 辅助索引同步失败只记录日志的问题。

目标：

1. 在线入库阶段辅助索引同步失败时，写入 Redis 延迟补偿队列。
2. Worker 主循环消费到期补偿任务。
3. 补偿任务按 `sourceName` 定向修复辅助索引权限投影，不重新解析文档，不重建向量。

## 二、关键变更

### 1. 在线失败入队

文件：

```text
ai_service/core/rag_pipeline.py
```

新增：

```text
AUXILIARY_INDEX_RETRY_QUEUE
AUXILIARY_INDEX_RETRY_DELAY_SECONDS
_enqueue_auxiliary_index_retry_from_env(...)
```

接入点：

1. `kb_doc_meta` 更新失败后入队。
2. `kb_doc_search` 首次失败后会即时重试一次，重试仍失败才入队。

### 2. Worker 到期消费

文件：

```text
ai_service/task_worker.py
```

新增：

```text
drain_due_auxiliary_index_retries(...)
```

主循环中执行顺序：

```text
drain_due_java_callback_retries(redis_client)
drain_due_auxiliary_index_retries(redis_client, pipeline)
drain_due_retry_payloads(redis_client)
```

### 3. 按 source 定向修复

文件：

```text
ai_service/scripts/backfill_auxiliary_permission_projection.py
```

新增：

```text
source_projection_query(...)
scan_aux_docs_by_source(...)
backfill_source(...)
```

修复逻辑：

```text
sourceName -> 查询 kb_doc_meta/kb_doc_search 文档级记录
sourceName -> 反查 kb_document chunk 事实源
chunk._index -> source_index
chunk 权限字段 -> owner_unit_code/visible_unit_codes
原地 update 辅助索引
```

## 三、测试命令与结果

### 1. 辅助索引补齐脚本测试

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

覆盖点：

1. 全量巡检仍只定位缺失 `source_index/visible_unit_codes` 的辅助文档。
2. 单 source 查询只匹配目标文档。
3. 修复字段保持最小集：`source_index/index_code/owner_unit_code/visible_unit_codes/permission_version`。
4. 写回使用辅助索引真实物理 `_index`，避免别名扫描后写错目标。

### 2. Worker 补偿队列测试

命令：

```powershell
python ai_service\tools_and_tests\test_auxiliary_index_retry.py
```

结果：

```text
PASS auxiliary index retry tests
```

覆盖点：

1. 到期任务成功修复后，从 Redis zset 移除。
2. 修复失败时，任务重新写回 zset，并记录 `lastError`。

### 3. 回归测试

命令：

```powershell
python ai_service\tools_and_tests\test_java_callback_retry.py
python ai_service\tools_and_tests\test_acl_payload_policy.py
```

结果：

```text
PASS java callback retry tests
PASS acl payload policy tests
```

## 四、结论

1. 主 chunk 入库成功但辅助索引同步失败时，系统现在具备自动补偿能力。
2. 补偿任务轻量、幂等，只依赖 ES chunk 事实源，不依赖重新解析原文件。
3. 本轮未连接真实 Redis/ES 做端到端实测，已完成本地可重复单元级验证。

## 五、剩余建议

1. 后续可增加补偿队列 DLQ，避免单个异常 source 无限重排。
2. 可在监控中加入 `AUXILIARY_INDEX_RETRY_QUEUE` 长度和 oldest retry age。
3. 可将 `AUXILIARY_RETRY_INDEXES` 在生产配置中显式设置为 `kb_doc_search,kb_doc_meta`。
