# P1 任务测试报告：QA 索引补充 source_index 权限投影字段

## 1. 任务目标

为 `kb_qa_pairs` QA 聚合索引补充文档级权限投影字段，使后续 QA 召回可以按角色可读物理索引范围过滤。

本批只处理 QA mapping 和新写入字段，不启用查询侧 `source_index` 过滤。查询侧过滤需等独立 QA Worker 透传和历史数据兼容策略一起完成后再启用。

## 2. 问题定位

当前事实：

- Java `AiEngineGateway.fetchQaResults(...)` 和 `fetchQaResultsByBm25(...)` 已向 Python 传入 `acl_tokens`。
- Python `/api/ai/qa/search` 和 `/api/ai/qa/search/bm25` 已使用 `acl_tokens` 做 QA 文档权限过滤。
- `kb_qa_pairs` 新建 mapping 只有 `source/doc_version/is_latest/acl_tokens` 等字段，缺少 `source_index`。
- `RAGPipeline._generate_and_index_qa_pairs(...)` 写入 QA 时未记录原 chunk 物理索引。

第一性原理结论：

- QA 索引是聚合索引，不随 `kb_document_*` 物理索引拆分。
- 若 QA 记录不带 `source_index`，后续无法根据角色可读索引范围进行预过滤。
- 因此应先让新 QA 数据具备权限投影字段，再灰度开启查询过滤。

## 3. 本次修改范围

### 3.1 `ai_service/core/indexing/es_setup.py`

新增 QA mapping 字段：

- `source_index: keyword`
- `index_code: keyword`
- `owner_unit_code: keyword`
- `visible_unit_codes: keyword`
- `permission_version: long`

覆盖路径：

- 新建 `kb_qa_pairs` 时包含上述字段。
- `_update_mapping()` 对已有 `kb_qa_pairs` 追加上述字段。

### 3.2 `ai_service/core/rag_pipeline.py`

新增：

- `_qa_permission_projection(source_index, ext_metadata)`

新增写入字段：

- `source_index`
- `index_code`
- `owner_unit_code`
- `visible_unit_codes`
- `permission_version`

调整：

- Redis `QUEUE_QA` payload 增加 `targetIndex/ownerUnitCode/visibleUnitCodes/permissionVersion`。
- Redis 推送失败后的降级线程也传入 `source_index=target_index` 和 `ext_metadata`。
- `_generate_and_index_qa_pairs(...)` 增加可选参数 `source_index` 和 `ext_metadata`。

### 3.3 `ai_service/task_worker.py`

内嵌 QA Worker 新增透传：

- 从 payload 读取 `targetIndex`
- 从 payload 读取 `docVersion`
- 从 payload 读取单位权限字段
- 调用 `_generate_and_index_qa_pairs(...)` 时传入上述字段

## 4. 验证记录

### 4.1 Python 语法检查

执行命令：

```powershell
python -m py_compile ai_service/core/indexing/es_setup.py ai_service/core/rag_pipeline.py ai_service/task_worker.py
```

结果：

- 通过。

### 4.2 静态字段核对

执行命令：

```powershell
rg -n "def _qa_permission_projection|source_index.*qa_permission|targetIndex|QA 权限字段|visible_unit_codes|permission_version" ai_service/core/indexing/es_setup.py ai_service/core/rag_pipeline.py ai_service/task_worker.py
```

结果：

- QA mapping 中存在 `source_index/index_code/owner_unit_code/visible_unit_codes/permission_version`。
- QA 写入体中存在上述字段。
- `QUEUE_QA` payload 中存在 `targetIndex`。
- 内嵌 QA Worker 会把 `targetIndex` 透传给 `_generate_and_index_qa_pairs(...)`。

## 5. 影响分析

### 5.1 正向影响

- 新写入 QA 记录可以定位原 chunk 物理索引。
- 为后续“角色可查看某些索引/某些类型文档”的 QA 召回过滤打基础。
- QA 记录开始具备单位权限扩展字段，后续可接入 `visible_unit_codes`。

### 5.2 兼容性

- 不改变当前 QA 查询行为。
- 历史 QA 记录缺失 `source_index` 不会受到影响。
- `permission_version` 做了安全转换，非数字输入降级为 `0`，不影响 QA 写入。

## 6. 未完成项与原因

本批未处理：

- `ai_service/task_worker_qa.py` 独立 QA Worker。
- Java `AiEngineGateway` 向 Python QA 接口传 `resolvedIndexPattern`。
- Python QA 查询按 `source_index` 过滤。
- 历史 QA 数据回填 `source_index`。

原因：

- 按单批不超过 3 个代码文件的边界，本批先完成主入库与内嵌 Worker 的字段写入能力。
- 查询侧过滤应等所有写入入口一致后再启用，否则可能导致 QA 召回突然下降。

## 7. 建议后续任务

1. 补 `ai_service/task_worker_qa.py` 独立 QA Worker 的 `targetIndex/docVersion/单位字段` 透传。
2. Java QA payload 增加 `resolvedIndexPattern`。
3. Python QA KNN/BM25 查询增加 `source_index` 过滤，旧数据缺失字段时走兼容分支。
4. 编写 QA 历史数据回填脚本，从 chunk 或 registry 补齐 `source_index`。

## 8. 结论

本任务已完成。

`kb_qa_pairs` 已具备 `source_index` 权限投影字段，新 QA 写入路径和内嵌 Worker 已开始写入相关字段。Python 语法检查和静态核对均通过。
