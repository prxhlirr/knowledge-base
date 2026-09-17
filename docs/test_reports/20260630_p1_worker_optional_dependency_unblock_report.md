# P1 Worker 可选依赖启动阻塞修复测试报告

## 1. 任务背景

本轮任务承接“完整 ES Worker 入库闭环验证”。在执行 Worker 入口验证时，发现本地 Python 环境缺少 `markitdown`，后续又暴露缺少 `jieba`、`bs4` 等依赖。

从第一性原理判断：

- `markitdown` 是通用文档解析增强器，不是权限字段投影、索引路由、ES 写入的根能力。
- `jieba` 只用于文档级和 chunk 级关键词提取，不应阻断权限闭环验证。
- `bs4` 只在 HTML 兜底文本抽取时需要，不应成为普通文本入库的硬启动条件。
- 向量化、ONNX、ES 客户端等仍属于正式入库检索链路硬能力，不能静默吞掉。

因此本轮只对非核心增强依赖做降级处理，不改变权限规则、不改变 ES mapping、不改变主检索召回逻辑。

## 2. 本轮代码变更

### 2.1 `ai_service/core/rag_pipeline.py`

已验证并实施：

1. `markitdown` 改为可选导入。
2. 新增 `_FallbackMarkItDown`，在缺少 `markitdown` 时为 `txt/html` 提供最小可用解析兜底。
3. `jieba.analyse` 改为可选导入。
4. 新增 `_extract_keywords(text, top_k)`：
   - 优先使用 `jieba` 的 TF-IDF 关键词提取；
   - 缺少 `jieba` 时，使用保守正则提取中文词、英文词、数字编号；
   - 保持返回值始终为 `list`，避免下游 `keywords` 字段形态变化。
5. HTML 兜底解析去掉 `bs4` 硬依赖，改用标准库 `html` + 正则移除标签。

## 3. 测试过程

### 3.1 缺失依赖确认

本地环境确认：

- `markitdown`：缺失。
- `jieba`：缺失。
- `bs4`：缺失。
- `transformers`、`onnxruntime`、`numpy`、`elasticsearch`、`tqdm`：存在。

### 3.2 `rag_pipeline` 导入验证

命令：

```powershell
python -c "import sys; sys.path.insert(0, 'ai_service'); import core.rag_pipeline as rp; print('rag_pipeline_import_ok'); print(rp._extract_keywords('兰州市公安局620102文档权限测试', 5))"
```

结果：

- 通过。
- 输出 `rag_pipeline_import_ok`。
- 关键词兜底输出 `['兰州市公安局', '620102', '文档权限测试']`。

说明：

- 缺少 `markitdown/jieba/bs4` 不再阻断 `rag_pipeline` 导入。
- 关键词字段仍保持列表形态。

### 3.3 HTML 兜底解析验证

命令：

```powershell
python -c "import tempfile, pathlib, sys; sys.path.insert(0, 'ai_service'); from core.rag_pipeline import _FallbackMarkItDown; p=pathlib.Path(tempfile.gettempdir())/'kb_fallback_parser_test.html'; p.write_text('<html><script>x</script><body><h1>标题</h1><p>正文权限测试</p></body></html>', encoding='utf-8'); print(_FallbackMarkItDown().convert(str(p)).text_content)"
```

结果：

```text
标题
正文权限测试
```

说明：

- `script` 内容已被过滤。
- HTML 标签已被剥离。
- 未依赖 `bs4`。

### 3.4 Worker 入口导入验证

命令：

```powershell
python -c "import sys, types; sys.path.insert(0, 'ai_service'); import task_worker; print('task_worker_import_ok')"
```

结果：

- 通过。
- 输出 `task_worker_import_ok`。

说明：

- 普通 Worker 模块入口可导入。
- 本轮修复没有破坏前序 `task_worker.py` 权限投影逻辑。

### 3.5 已有权限投影单测回归

命令：

```powershell
python ai_service/tools_and_tests/test_task_worker_permission_projection.py
```

结果：

```text
Ran 4 tests in 0.000s
OK
```

说明：

- Redis payload 到 Worker ext_meta 的单位权限字段投影仍然正确。
- `owner_unit_code`、`visible_unit_codes`、`permission_version` 等字段未受本轮变更影响。

### 3.6 语法编译检查

命令：

```powershell
python -m py_compile ai_service/core/rag_pipeline.py ai_service/task_worker.py ai_service/task_worker_qa.py ai_service/core/permissions/payload_projection.py
```

结果：

- 通过，无语法错误。

## 4. 额外发现

### 4.1 `rag_pipeline` 顶层导入仍然偏重

使用 `python -X importtime` 验证后，`rag_pipeline` 顶层导入约 23 秒，主要耗时来自：

- `core.model_manager`
- `transformers`
- `sklearn`
- `pandas`
- `onnxruntime`

该问题不是本轮可选依赖修复导致的，而是现有架构中模型管理器在模块导入期引入重依赖。

影响：

- Worker 冷启动较慢。
- 单元测试导入成本高。
- 权限投影、payload 构建这类轻逻辑被迫等待模型相关依赖加载。

建议作为下一步独立任务处理：

- 将 `model_manager` 的重型导入延迟到首次向量化/重排调用时。
- 保证权限投影、解析路由、payload 校验等轻路径不触发模型依赖。
- 增加导入耗时测试或最小导入 smoke test，防止后续再次把重依赖放回顶层。

## 5. 结论

本轮任务已完成。

已达成：

- 缺少 `markitdown` 不再阻断纯文本/HTML 最小解析。
- 缺少 `jieba` 不再阻断关键词字段生成。
- 缺少 `bs4` 不再阻断 HTML 兜底解析。
- Worker 入口导入通过。
- 前序权限投影单测通过。
- Python 语法编译通过。

未在本轮完成：

- 尚未执行完整 ES Worker 实际消费队列并写入 ES 的闭环测试。

原因：

- 现在已经越过非核心依赖阻塞，但 `rag_pipeline` 顶层重依赖导入仍然显著拖慢启动。
- 下一步应先处理“模型重依赖延迟加载”，再执行完整 Worker 入库到 ES 的端到端验证，避免用超长启动时间掩盖架构问题。

