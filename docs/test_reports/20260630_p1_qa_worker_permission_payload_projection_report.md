# P1 独立 QA Worker 权限 Payload 投影统一报告

## 本轮目标

将独立 `task_worker_qa.py` 的权限 payload 解析逻辑与普通 `task_worker.py` 对齐，避免两个 Worker 对历史 payload、新字段 payload 的兼容能力不一致。

## 根因

普通 Worker 已使用 `build_permission_projection_from_payload(payload)` 兼容：

- Java camelCase 字段：`ownerUnitCode / visibleUnitCodes / permissionVersion`
- Python snake_case 字段：`owner_unit_code / visible_unit_codes / permission_version`
- 历史字段：`deptCode`
- 字符串形式的 `visibleUnitCodes`
- 缺失字段时回退 `global`

但独立 QA Worker 仍手写读取：

```python
{
    "owner_unit_code": payload.get("ownerUnitCode", ""),
    "visible_unit_codes": payload.get("visibleUnitCodes", []),
    "permission_version": payload.get("permissionVersion", 0),
}
```

这会导致历史队列消息或恢复任务 payload 在独立 QA Worker 中丢失兼容兜底。

## 实施内容

### 1. 新增公共轻量模块

文件：

```text
ai_service/core/permissions/payload_projection.py
```

新增：

```python
build_permission_projection_from_payload(payload)
```

该模块不依赖 `RAGPipeline`，因此独立 QA Worker 导入时不会提前加载大模型链路。

### 2. 普通 Worker 改为导入公共函数

文件：

```text
ai_service/task_worker.py
```

移除本地函数定义，改为：

```python
from core.permissions.payload_projection import build_permission_projection_from_payload
```

### 3. 独立 QA Worker 改为复用公共函数

文件：

```text
ai_service/task_worker_qa.py
```

`qa_meta` 改为：

```python
qa_meta = build_permission_projection_from_payload(payload)
```

### 4. 单测改为直接测试公共模块

文件：

```text
ai_service/tools_and_tests/test_task_worker_permission_projection.py
```

不再 mock `RAGPipeline`，测试更轻、更稳定。

## 测试记录

### Python 单测

```bash
python ai_service/tools_and_tests/test_task_worker_permission_projection.py
```

结果：

```text
Ran 4 tests in 0.001s
OK
```

### 公共模块导入验证

```bash
python -c "import sys; sys.path.insert(0, 'ai_service'); from core.permissions.payload_projection import build_permission_projection_from_payload; print(build_permission_projection_from_payload({'deptCode':'620102'}))"
```

结果：

```text
{'owner_unit_code': '620102', 'visible_unit_codes': ['620102'], 'permission_version': 0}
```

### 独立 QA Worker 导入验证

```bash
python -c "import sys; sys.path.insert(0, 'ai_service'); import task_worker_qa; print('qa import ok')"
```

结果：

```text
qa import ok
```

### 普通 Worker 导入验证

使用 `core.rag_pipeline` stub 避免本机缺少 `markitdown` 影响导入：

```bash
python -c "import sys, types; sys.path.insert(0, 'ai_service'); m=types.ModuleType('core.rag_pipeline'); m.RAGPipeline=object; sys.modules['core.rag_pipeline']=m; import task_worker; print(task_worker.build_permission_projection_from_payload({'ownerUnitCode':'A','visibleUnitCodes':'A,B'}))"
```

结果：

```text
{'owner_unit_code': 'A', 'visible_unit_codes': ['A', 'B'], 'permission_version': 0}
```

## 结论

独立 QA Worker 与普通 Worker 已统一使用同一套权限 payload 投影逻辑。后续 Java 正常入库、恢复任务重推、历史 payload 进入 QA Worker 时，单位权限字段解析行为保持一致。

## 后续任务

1. 补齐本机或容器 AI Worker 依赖，执行真实 ES 落库闭环。
2. 验证 `kb_qa_pairs` 中的 `source_index / owner_unit_code / visible_unit_codes / permission_version` 与 chunk 索引一致。
