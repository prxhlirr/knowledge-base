# P1 任务测试报告：batch_colloquial_generator 默认禁用 colloquial_vector 写入

## 1. 任务目标

治理历史 `colloquial_vector` 回填脚本，避免字段优化后被旧运维命令重新写入冗余向量。

本批只处理三个 `batch_colloquial_generator.py` 副本，控制变更范围不超过 3 个代码文件。旧 `ai_service/scripts/rag_pipeline.py` 中的后台异步回填线程单独作为下一批任务处理。

## 2. 问题定位

代码扫描确认仍可写入 `colloquial_vector` 的 batch 脚本：

- `scripts/batch_colloquial_generator.py`
- `ai_service/scripts/batch_colloquial_generator.py`
- `ai_service/tools_and_tests/batch_colloquial_generator.py`

这些脚本默认执行路径会：

1. 扫描缺失 `colloquial_vector` 的 fine chunk。
2. 调用 `/api/ai/colloquial/generate` 生成短语。
3. 调用 `/api/ai/vector/query` 生成向量。
4. 使用 ES update 写回 `colloquial_vector`。

第一性原理结论：

- `colloquial_vector` 已不参与当前主检索链路。
- 新索引优化目标是不再承载该冗余向量。
- 因此历史脚本不能保持“默认可写”，否则新 mapping/迁移策略会被运维脚本破坏。

## 3. 本次修改范围

三个脚本均新增：

- `ALLOW_DEPRECATED_WRITE_ENV = "ALLOW_DEPRECATED_COLLOQUIAL_VECTOR_WRITE"`
- `deprecated_write_allowed(args)`
- `stop_if_deprecated_write_not_allowed(args)`
- 命令行参数 `--allow-deprecated-colloquial-vector`

默认行为：

- `--dry-run`：允许执行，只统计，不写 ES。
- 不传任何授权参数：阻断执行，退出前不连接 ES。
- 显式传 `--allow-deprecated-colloquial-vector`：允许历史回填。
- 设置 `ALLOW_DEPRECATED_COLLOQUIAL_VECTOR_WRITE=true`：允许历史回填。

## 4. 验证记录

### 4.1 Python 语法检查

执行命令：

```powershell
python -m py_compile scripts/batch_colloquial_generator.py ai_service/scripts/batch_colloquial_generator.py ai_service/tools_and_tests/batch_colloquial_generator.py
```

结果：

- 通过。

### 4.2 默认阻断路径验证

执行命令：

```powershell
python scripts/batch_colloquial_generator.py --limit 1
python ai_service/scripts/batch_colloquial_generator.py --limit 1
python ai_service/tools_and_tests/batch_colloquial_generator.py --limit 1
```

结果：

- 三个脚本均输出 `[BLOCKED] colloquial_vector 已从主检索链路废弃，默认禁止回填写入。`
- 三个脚本均提示使用 `--dry-run` 或显式授权参数。
- 三个脚本均在 ES 连接前阻断。

验证过程中发现并修复的问题：

- 初始阻断提示使用了 Windows GBK 控制台无法输出的符号，导致 `UnicodeEncodeError`。
- 已改为 ASCII 前缀 `[BLOCKED]`，复测通过。

### 4.3 参数静态核对

执行命令：

```powershell
rg -n "ALLOW_DEPRECATED_COLLOQUIAL_VECTOR_WRITE|allow-deprecated-colloquial-vector|stop_if_deprecated_write_not_allowed|deprecated_write_allowed|\[BLOCKED\]" scripts/batch_colloquial_generator.py ai_service/scripts/batch_colloquial_generator.py ai_service/tools_and_tests/batch_colloquial_generator.py
```

结果：

- 三个脚本均包含环境变量开关。
- 三个脚本均包含命令行显式授权参数。
- 三个脚本均在 `main()` 解析参数后立即调用 `stop_if_deprecated_write_not_allowed(args)`。

## 5. 环境提示

执行脚本时出现既有依赖 warning：

```text
RequestsDependencyWarning: urllib3 ... or chardet/charset_normalizer ... doesn't match a supported version
```

该 warning 来自当前 Python 环境依赖版本组合，不影响本次阻断逻辑和语法检查结论。

## 6. 影响分析

### 6.1 正向影响

- 默认运维路径不再写入 `colloquial_vector`。
- `--dry-run` 仍可用于统计历史字段缺口。
- 如确有临时兼容需要，必须显式授权，行为可审计。

### 6.2 兼容性

- 不删除脚本，不破坏历史排查能力。
- 不修改 ES mapping，不触碰历史数据。
- 默认执行从“写入”变为“阻断”，符合字段下线目标。

## 7. 未完成项

本批未修改：

- `ai_service/scripts/rag_pipeline.py`

原因：

- 该文件包含旧入库管线和后台 daemon 回填逻辑，改动点与 batch 脚本不同。
- 为遵守单批不修改超过 3 个代码文件的边界，下一批单独处理。

## 8. 建议后续测试

下一批处理 `ai_service/scripts/rag_pipeline.py` 后建议验证：

1. 默认入库不会启动 `AsyncColloquial` 后台线程。
2. 显式环境变量开启时才允许旧线程运行。
3. 新索引 mapping 中不再依赖 `colloquial_vector`。

## 9. 结论

本任务已完成。

三个 `batch_colloquial_generator.py` 历史回填脚本已从默认写入改为默认阻断，语法检查和默认阻断路径均已验证。
