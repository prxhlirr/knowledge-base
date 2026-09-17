# P1 任务测试报告：新索引模板移除 colloquial_vector mapping

## 1. 任务目标

在已禁用 `colloquial_vector` 写入入口之后，继续治理新建索引 mapping，确保后续新建的 `kb_document_*` 索引不再定义 `colloquial_vector` 字段。

本批只处理主链路新索引模板和热更新 mapping，不处理历史索引中的既有字段。历史字段需通过后续 reindex/alias 切换自然淘汰。

## 2. 问题定位

代码扫描确认以下位置仍会为新索引或热更新路径定义 `colloquial_vector`：

- `ai_service/core/indexing/es_setup.py`
  - `kb_document_template` 模板中定义 `"colloquial_vector": {"type": "dense_vector", ...}`
- `ai_service/core/rag_pipeline.py`
  - `_update_mapping()` 中定义 `colloquial_vector`
  - `_ensure_index_template()` 中定义 `colloquial_vector`

第一性原理结论：

- `colloquial_vector` 已不参与 Java 主检索链路。
- dense_vector 即使 `index:false`，也会增加存储和序列化成本。
- 新索引模板不应继续承载已废弃字段。

## 3. 本次修改范围

### 3.1 `ai_service/core/indexing/es_setup.py`

移除：

- `kb_document_template` 中的 `colloquial_vector` 字段定义。

保留：

- `_update_mapping()` 中“已从热更新移除”的说明注释。

### 3.2 `ai_service/core/rag_pipeline.py`

移除：

- `_update_mapping()` 中的 `colloquial_vector` 字段定义。
- `_ensure_index_template()` 中的 `colloquial_vector` 字段定义。

保留：

- 主链路中“后台异步回填线程已移除”的废弃说明注释。

## 4. 验证记录

### 4.1 Python 语法检查

执行命令：

```powershell
python -m py_compile ai_service/core/indexing/es_setup.py ai_service/core/rag_pipeline.py
```

结果：

- 通过。

### 4.2 精确字段定义扫描

执行命令：

```powershell
rg -n '"colloquial_vector"\s*:' ai_service/core/indexing/es_setup.py ai_service/core/rag_pipeline.py
```

结果：

- 无命中。

结论：

- 主链路新模板和热更新 mapping 中已不存在 `colloquial_vector` 字段定义。

### 4.3 普通关键词扫描

执行命令：

```powershell
rg -n "colloquial_vector" ai_service/core/indexing/es_setup.py ai_service/core/rag_pipeline.py
```

结果：

- 仅剩废弃说明注释。
- 无实际 mapping 字段定义。

## 5. 影响分析

### 5.1 正向影响

- 后续通过 `kb_document_template` 新建的 `kb_document_*` 索引不再包含 `colloquial_vector`。
- 主链路 `_update_mapping()` 不再尝试给既有索引追加该字段。
- 与前两批“默认不写入”保护形成闭环：新字段不建，旧入口不写。

### 5.2 兼容性

- 不删除历史索引中已存在的字段。
- 不改 Java 查询逻辑，因为 Java 主链路已不使用该字段。
- 不影响 `vector` 和 `sparse_vector` 主召回字段。

## 6. 未完成项与原因

未处理历史 ES 索引中已存在的 `colloquial_vector` 数据。

原因：

- ES mapping 不能直接删除字段。
- 历史字段清理应放到 P2 reindex/alias 切换阶段执行。

## 7. 建议后续测试

1. 在测试 ES 中删除并重建一个临时 `kb_document_xxx` 索引，确认 mapping 不包含 `colloquial_vector`。
2. 执行一条新文档入库，确认 `_source` 不包含 `colloquial_vector`。
3. P2 reindex 时确认目标索引 count 与权限字段一致，并自然丢弃历史 `colloquial_vector`。

## 8. 结论

本任务已完成。

主链路新索引模板和热更新 mapping 已移除 `colloquial_vector` 字段定义，Python 语法检查和静态扫描均通过。
