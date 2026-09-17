# P1 Worker 核心入库权限字段 E2E 测试报告

## 1. 本轮目标

验证真实 Worker 核心入库链路是否会把新权限控制字段完整写入：

- 主 chunk 索引：`kb_document_law`
- 文档元索引：`kb_doc_meta_v2`
- 文档搜索索引：`kb_doc_search_v1`

核心字段：

- `source_index`
- `index_code`
- `owner_unit_code`
- `visible_unit_codes`
- `permission_version`

## 2. 第一性原理结论

单位权限过滤最终发生在 ES 查询层。

Java 检索侧会过滤：

- `visible_unit_codes`
- `metadata.visible_unit_codes`

因此文档入库时必须保证：

1. 主 chunk 文档顶层存在 `visible_unit_codes`；
2. 主 chunk 文档 `metadata.visible_unit_codes` 也存在；
3. `kb_doc_search_v1` 文档级预召回索引也存在同样的权限字段；
4. `kb_doc_meta_v2` 用于文档级能力时也要携带同样的权限投影；
5. 权限字段不能被 strict mapping fallback 静默丢弃。

## 3. 发现的问题

### 3.1 主索引 chunk 未写入单位权限字段

代码验证位置：

- `ai_service/core/rag_pipeline.py`

问题：

- 原 chunk 只写了 `metadata.owner_dept_id`；
- 没有写 `source_index/index_code/owner_unit_code/visible_unit_codes/permission_version`；
- Java 查询过滤 `visible_unit_codes` 时，新入库文档可能被误过滤。

### 3.2 `kb_doc_search_v1` 缺少权限字段 mapping

真实 ES mapping 验证结果：

```text
kb_doc_search_v1:
source_index=None
index_code=None
owner_unit_code=None
visible_unit_codes=None
permission_version=None
```

第一次 E2E 失败原因：

```text
[DocIndexer] strict mapping lacks optional fields [...]; retry without them
AssertionError: None != 'kb_document_law'
```

根因：

- `kb_doc_search_v1` 是 `dynamic: strict`；
- 当前运行环境中的历史索引没有这些字段；
- `DocIndexer` 把权限字段当 optional 字段丢弃后重试，导致 `kb_doc_search_v1` 仍然没有权限字段。

### 3.3 `kb_doc_meta_v2` 历史 mapping 类型不一致

真实 ES mapping 验证结果：

```text
kb_doc_meta_v2:
source_index=text + keyword
index_code=text + keyword
owner_unit_code=text + keyword
visible_unit_codes=text + keyword
permission_version=long
```

影响：

- 字段值可以写入；
- 但类型不是目标设计中的 `keyword`；
- ES 不允许原地把 `text` 改成 `keyword`，后续需要 reindex 到新索引修正。

本轮未直接重建 `kb_doc_meta_v2`，避免破坏现有数据。

## 4. 本轮代码修复

### 4.1 `ai_service/core/rag_pipeline.py`

修复：

- 在 chunk 构造阶段生成统一权限投影；
- 主索引顶层写入：
  - `source_index`
  - `index_code`
  - `owner_unit_code`
  - `visible_unit_codes`
  - `permission_version`
- 主索引 `metadata` 同步写入同样字段；
- `update_doc_meta()` 和 `update_doc_search()` 调用时传入真实 `target_index`。

### 4.2 `ai_service/core/indexing/doc_indexer.py`

修复：

- `permission_version` 改为沿用入库 payload 中的版本，不再写入当前毫秒时间；
- `update_doc_meta()` 和 `update_doc_search()` 从 chunk metadata 中读取权限投影；
- 权限字段不再作为 optional 字段被 strict mapping fallback 丢弃。

### 4.3 `ai_service/tools_and_tests/test_worker_core_permission_e2e.py`

新增真实 E2E：

- 创建临时本地 txt；
- 构造与 Worker 一致的 payload；
- 使用 `build_permission_projection_from_payload()` 构建权限投影；
- 调用真实 `RAGPipeline.process_and_index()`；
- 查询三个 ES 索引并断言权限字段；
- 清理测试 ES 文档和 `QUEUE_QA` 残留。

## 5. ES mapping 修复

本轮对当前本地 ES 执行了非破坏性热更新：

```python
es.indices.put_mapping(
    index="kb_doc_search_v1",
    properties={
        "source_index": {"type": "keyword"},
        "index_code": {"type": "keyword"},
        "owner_unit_code": {"type": "keyword"},
        "visible_unit_codes": {"type": "keyword"},
        "permission_version": {"type": "long"},
    },
)
```

执行后验证：

```text
kb_doc_search_v1:
source_index=keyword
index_code=keyword
owner_unit_code=keyword
visible_unit_codes=keyword
permission_version=long
```

## 6. 测试结果

### 6.1 语法检查

命令：

```powershell
python -m py_compile ai_service/core/rag_pipeline.py ai_service/core/indexing/doc_indexer.py ai_service/tools_and_tests/test_worker_core_permission_e2e.py
```

结果：

- 通过。

### 6.2 权限 payload 投影回归

命令：

```powershell
python ai_service/tools_and_tests/test_task_worker_permission_projection.py
```

结果：

```text
Ran 4 tests in 0.000s
OK
```

### 6.3 Worker 核心真实 E2E

命令：

```powershell
python ai_service/tools_and_tests/test_worker_core_permission_e2e.py
```

结果：

```text
Ran 1 test in 17.516s
OK
```

实际链路结果：

- ES safe 初始化通过；
- 文档解析通过；
- 双粒度切片：粗粒度 1 块，细粒度 4 块；
- ONNX CPU 向量化成功；
- `kb_document_law_write` bulk 写入 5 条成功；
- `kb_doc_meta_write` 同步成功；
- `kb_doc_search_write` 同步成功；
- 主索引、元索引、搜索索引权限字段断言全部通过。

### 6.4 清理验证

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

## 7. 剩余事项

### 7.1 `kb_doc_meta_v2` 需要后续 reindex

当前 `kb_doc_meta_v2` 的权限字段已存在，但类型是历史动态映射产生的 `text + keyword`，不是目标 `keyword`。

后续建议：

1. 创建 `kb_doc_meta_v3`，使用标准 mapping；
2. 从 `kb_doc_meta_v2` reindex 到 `kb_doc_meta_v3`；
3. 原子切换 `kb_doc_meta_read/kb_doc_meta_write` 别名；
4. 验证相似文档、元数据检索和权限字段过滤；
5. 确认无误后保留旧索引一段时间再清理。

### 7.2 完整队列 Worker 主循环仍需单独验证

本轮验证的是 Worker 核心入库链路，没有启动无限循环 `task_worker.main()` 消费 Redis 队列。

原因：

- 主循环成功后会回调 Java；
- 当前 E2E 使用不可达 Java 地址隔离外部副作用；
- 直接启动无限循环不利于测试自动收口。

下一步建议：

- 抽取单任务处理函数，例如 `process_payload_once(payload, pipeline, redis_client)`；
- 主循环和 E2E 测试共用该函数；
- 再做真正的 Redis 入队 -> Worker 单任务消费 -> ES 校验 -> 自动退出测试。

## 8. 结论

本轮任务已完成。

结论：

- 主索引 chunk 权限字段缺失问题已修复；
- `kb_doc_search_v1` 权限字段 mapping 缺失问题已修复；
- 权限字段不再允许被 strict mapping fallback 静默丢弃；
- Worker 核心入库链路已通过真实 ES、Redis、ONNX E2E 验证；
- 测试数据和 QA 队列残留已清理。

