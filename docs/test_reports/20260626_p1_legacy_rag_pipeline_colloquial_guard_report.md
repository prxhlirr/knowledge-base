# P1 任务测试报告：旧 rag_pipeline 默认禁用 AsyncColloquial 回填

## 1. 任务目标

治理 `ai_service/scripts/rag_pipeline.py` 旧入库管线中的 `AsyncColloquial` 后台线程，避免旧管线在文档入库后自动写入已废弃的 `colloquial_vector` 字段。

上一批已处理三个 `batch_colloquial_generator.py` 人工回填脚本；本批处理旧入库管线自动回填入口。

## 2. 问题定位

文件：

- `ai_service/scripts/rag_pipeline.py`

代码扫描确认：

- `AsyncColloquial` 后台线程位于旧管线 bulk 写入 ES 之后。
- 线程会对 fine chunk 调用 `/api/ai/colloquial/generate`。
- 然后调用 `model_manager.encode(...)` 生成向量。
- 最后通过 ES update 写入 `colloquial_vector`。

第一性原理结论：

- `colloquial_vector` 已不参与当前 Java 主检索链路。
- 新索引优化目标是不再默认写入该冗余向量。
- 旧入库管线的自动后台线程比人工 batch 脚本风险更高，因为它会在文档入库后自动触发。

## 3. 本次修改范围

### 3.1 新增统一环境开关

新增常量：

```python
ALLOW_DEPRECATED_COLLOQUIAL_VECTOR_WRITE_ENV = "ALLOW_DEPRECATED_COLLOQUIAL_VECTOR_WRITE"
```

新增方法：

```python
def deprecated_colloquial_vector_write_enabled() -> bool:
```

规则：

- 默认返回 `False`。
- 仅当环境变量 `ALLOW_DEPRECATED_COLLOQUIAL_VECTOR_WRITE=true` 时返回 `True`。
- 与上一批 batch 脚本使用同一个开关名，避免运维口径分裂。

### 3.2 默认跳过 AsyncColloquial

旧逻辑：

- bulk 写入 ES 后，只要存在 fine chunk，就启动后台线程写入 `colloquial_vector`。

新逻辑：

- 只有 `deprecated_colloquial_vector_write_enabled()` 为 `True` 时才构造 fine chunk 列表并启动后台线程。
- 默认路径输出：

```text
[AsyncColloquial] 默认跳过；如确需旧字段回填，请设置 ALLOW_DEPRECATED_COLLOQUIAL_VECTOR_WRITE=true
```

## 4. 验证记录

### 4.1 Python 语法检查

执行命令：

```powershell
python -m py_compile ai_service/scripts/rag_pipeline.py
```

结果：

- 通过。

### 4.2 静态核对

执行命令：

```powershell
rg -n "ALLOW_DEPRECATED_COLLOQUIAL_VECTOR_WRITE" ai_service/scripts/rag_pipeline.py
rg -n "deprecated_colloquial_vector_write_enabled" ai_service/scripts/rag_pipeline.py
rg -n "默认跳过" ai_service/scripts/rag_pipeline.py
rg -n "colloquial_vector" ai_service/scripts/rag_pipeline.py
```

结果：

- `ALLOW_DEPRECATED_COLLOQUIAL_VECTOR_WRITE` 存在。
- `deprecated_colloquial_vector_write_enabled()` 存在。
- `AsyncColloquial` 启动前已增加开关判断。
- `colloquial_vector` 的 ES update 写入语句仍保留，但只在显式开关开启后执行。

## 5. 影响分析

### 5.1 正向影响

- 旧入库管线默认不会再启动 `AsyncColloquial` 后台线程。
- 默认入库不会继续写入 `colloquial_vector`。
- 若确有历史兼容需求，可通过环境变量临时开启，行为明确可审计。

### 5.2 兼容性

- 不删除旧线程逻辑，降低历史回滚风险。
- 不改变 bulk 主写入流程。
- 不影响 Q&A 预置索引生成和 `kb_doc_meta` 更新。

## 6. 未改项与原因

本批未移除旧脚本 mapping 中的 `colloquial_vector` 定义：

- `ai_service/scripts/rag_pipeline.py` 中仍能扫描到 mapping 字段定义。
- 本批目标是阻断“继续写入”，不是重构旧索引 mapping。
- mapping 清理应与新索引模板治理合并处理，避免旧脚本启动时因历史索引兼容问题产生额外风险。

## 7. 建议后续测试

1. 在未设置 `ALLOW_DEPRECATED_COLLOQUIAL_VECTOR_WRITE` 时执行旧管线入库，日志应显示默认跳过 `AsyncColloquial`。
2. 设置 `ALLOW_DEPRECATED_COLLOQUIAL_VECTOR_WRITE=true` 后执行小样本入库，才允许启动旧线程。
3. 后续新索引模板任务中移除或降级旧 mapping 中的 `colloquial_vector`。

## 8. 结论

本任务已完成。

`ai_service/scripts/rag_pipeline.py` 已将 `AsyncColloquial` 自动回填改为显式 opt-in，默认不再写入 `colloquial_vector`。语法检查和静态核对均通过。
