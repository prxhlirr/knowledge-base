# P1 模型运行时延迟加载优化测试报告

## 1. 任务背景

上一轮已解决 `markitdown/jieba/bs4` 这类非核心增强依赖导致 Worker 无法启动的问题，但继续验证时发现 `rag_pipeline` 顶层导入仍约 23 秒。

从第一性原理分析：

- 权限字段投影、Redis payload 校验、Worker 入口加载不需要模型推理。
- `transformers`、`onnxruntime`、`sklearn`、`pandas` 属于向量化和重排热路径能力。
- 如果这些重依赖在模块导入期加载，会拖慢 Worker 冷启动，也会让权限控制相关轻量测试变慢。

因此本轮目标是：保留模型推理行为不变，只把重型运行时依赖从“模块导入期”后移到“首次真正推理前”。

## 2. 本轮代码变更

### 2.1 `ai_service/core/model_manager.py`

已实施：

1. 移除顶层重依赖导入：
   - `numpy`
   - `transformers.AutoTokenizer`
   - `onnxruntime`
   - `InferenceSession`

2. 新增 `_ensure_model_runtime_loaded()`：
   - 首次需要模型运行时时导入上述依赖；
   - 导入后缓存到模块全局变量；
   - 后续调用不重复导入。

3. 在真实模型运行时入口补充调用：
   - `_new_session_options()`
   - `_provider_pair()`
   - `load_model()`
   - `_load_session_with_fallback()`

### 2.2 `ai_service/tools_and_tests/test_model_manager_lazy_runtime.py`

新增回归测试：

1. `test_task_worker_import_does_not_load_model_runtime`
   - 独立子进程导入 `task_worker`；
   - 断言 `transformers/onnxruntime/sklearn/pandas` 未进入 `sys.modules`。

2. `test_explicit_session_options_loads_model_runtime`
   - 独立子进程调用 `model_manager._new_session_options()`；
   - 断言 `onnxruntime` 被显式加载。

## 3. 测试结果

### 3.1 `model_manager` 导入耗时验证

命令：

```powershell
python -c "import time, sys; sys.path.insert(0, 'ai_service'); t=time.perf_counter(); import core.model_manager as mm; print('model_manager_import_ms', int((time.perf_counter()-t)*1000)); print('runtime_loaded', mm.onnxruntime is not None)"
```

结果：

```text
model_manager_import_ms 25
runtime_loaded False
```

结论：

- `model_manager` 导入已降为毫秒级。
- 模型运行时未被提前加载。

### 3.2 `task_worker` 导入耗时验证

命令：

```powershell
python -c "import time, sys; sys.path.insert(0, 'ai_service'); t=time.perf_counter(); import task_worker; print('task_worker_import_ms', int((time.perf_counter()-t)*1000)); from core import model_manager as mm; print('onnxruntime_loaded', mm.onnxruntime is not None)"
```

结果：

```text
task_worker_import_ms 1088
onnxruntime_loaded False
```

结论：

- Worker 入口导入约 1.1 秒。
- `onnxruntime` 没有在 Worker 轻路径导入期加载。

### 3.3 显式运行时加载验证

命令：

```powershell
python -c "import time, sys; sys.path.insert(0, 'ai_service'); from core.model_manager import model_manager; import core.model_manager as mm; print('before', mm.np is not None, mm.onnxruntime is not None); t=time.perf_counter(); opt=model_manager._new_session_options(); print('runtime_load_ms', int((time.perf_counter()-t)*1000)); print('after', mm.np is not None, mm.onnxruntime is not None, type(opt).__name__)"
```

结果：

```text
before False False
runtime_load_ms 8152
after True True SessionOptions
```

结论：

- 延迟加载不是跳过加载。
- 真正进入模型运行时入口时，依赖能正常加载，并能创建 ONNX `SessionOptions`。

### 3.4 新增回归测试

命令：

```powershell
python ai_service/tools_and_tests/test_model_manager_lazy_runtime.py
```

结果：

```text
Ran 2 tests in 9.506s
OK
```

### 3.5 权限投影回归测试

命令：

```powershell
python ai_service/tools_and_tests/test_task_worker_permission_projection.py
```

结果：

```text
Ran 4 tests in 0.000s
OK
```

### 3.6 语法编译检查

命令：

```powershell
python -m py_compile ai_service/core/model_manager.py ai_service/tools_and_tests/test_model_manager_lazy_runtime.py
```

结果：

- 通过，无语法错误。

## 4. 影响分析

### 4.1 对权限控制链路的影响

无负面影响。

原因：

- 权限字段投影逻辑不依赖模型运行时；
- Worker payload 权限投影测试已回归通过；
- `owner_unit_code/visible_unit_codes/permission_version` 等字段不受本轮改动影响。

### 4.2 对入库向量化的影响

预期无业务语义变化。

原因：

- `encode/encode_sparse/encode_dual/rerank` 仍通过原有加载流程进入模型；
- 本轮只改变依赖导入时机；
- 显式 `_new_session_options()` 已验证可触发 `onnxruntime` 加载。

### 4.3 剩余风险

本轮未真实加载本地 ONNX 模型文件执行 `encode_dual`，因为该动作依赖本机模型文件和运行时资源，属于下一步完整 ES Worker 闭环验证的一部分。

## 5. 结论

本轮任务已完成。

已达成：

- `model_manager` 冷导入降至约 25ms。
- `task_worker` 冷导入约 1.1s。
- Worker 轻路径不再提前加载 `onnxruntime/transformers/sklearn/pandas`。
- 显式模型运行时入口仍能正常加载运行时依赖。
- 新增回归测试，防止后续重依赖回到顶层导入。
- 权限投影回归测试通过。

下一步建议：

- 执行完整 Worker 消费 Redis payload、解析文档、向量化、写入 ES、再按单位权限检索验证的端到端闭环测试。

