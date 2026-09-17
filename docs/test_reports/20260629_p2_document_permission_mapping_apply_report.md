# P2 普通文档权限投影 Mapping 补齐测试报告

## 1. 测试目标

为现有 `kb_document_*` 普通文档物理索引追加权限投影字段的显式 mapping，避免后续历史数据回填时依赖 ES 动态映射。

目标字段和类型：

| 字段 | 类型 | 原因 |
| --- | --- | --- |
| `source_index` | `keyword` | 按真实物理索引做精确过滤 |
| `index_code` | `keyword` | 按索引业务编码做精确过滤 |
| `owner_unit_code` | `keyword` | 按文档所属单位做精确过滤 |
| `visible_unit_codes` | `keyword` | 按可见单位集合做 terms 过滤 |
| `permission_version` | `long` | 记录权限投影版本，便于后续批量治理 |

## 2. 涉及文件

- `ai_service/scripts/apply_document_permission_mapping.py`
- `ai_service/tools_and_tests/test_apply_document_permission_mapping.py`
- `ai_service/scripts/backfill_document_permission_projection.py`
- `ai_service/tools_and_tests/test_backfill_document_permission_projection.py`

## 3. 新增脚本行为

`apply_document_permission_mapping.py` 默认 dry-run，只有传入 `--execute` 才执行真实 `put_mapping`。

脚本执行原则：

1. 只处理 `kb_document_` 前缀的物理索引。
2. 只追加缺失字段，不修改已有字段。
3. 如果已有字段类型与预期不一致，标记为 `conflicts`，不自动覆盖。
4. 支持重复执行，字段已存在时输出 `existing`，不产生写入。

## 4. 单元测试结果

执行命令：

```powershell
python -m py_compile ai_service/scripts/apply_document_permission_mapping.py ai_service/tools_and_tests/test_apply_document_permission_mapping.py ai_service/scripts/backfill_document_permission_projection.py ai_service/tools_and_tests/test_backfill_document_permission_projection.py
python ai_service/tools_and_tests/test_apply_document_permission_mapping.py
python ai_service/tools_and_tests/test_backfill_document_permission_projection.py
```

结果：

- `py_compile` 通过
- mapping 脚本单元测试 5 个全部通过
- 普通文档回填脚本单元测试 5 个全部通过

mapping 脚本测试覆盖：

- 缺失字段会生成追加计划
- 已有字段类型冲突会被识别
- 只处理 `kb_document_` 前缀索引
- dry-run 不调用 `put_mapping`
- execute 只提交缺失字段

## 5. 真实 ES 执行结果

### 5.1 执行前 dry-run

执行命令：

```powershell
$env:ES_HOST='http://127.0.0.1:9200'
python ai_service/scripts/apply_document_permission_mapping.py --index kb_document_*
```

结果：

| 指标 | 数值 |
| --- | ---: |
| indices | 6 |
| with_missing | 6 |
| applied | 0 |
| conflicts | 0 |

6 个索引均缺少 5 个权限投影字段，没有类型冲突。

### 5.2 真实执行

执行命令：

```powershell
$env:ES_HOST='http://127.0.0.1:9200'
python ai_service/scripts/apply_document_permission_mapping.py --index kb_document_* --execute
```

结果：

| 指标 | 数值 |
| --- | ---: |
| indices | 6 |
| with_missing | 6 |
| applied | 6 |
| conflicts | 0 |

已追加 mapping 的索引：

- `kb_document_law`
- `kb_document_news`
- `kb_document_notice`
- `kb_document_official`
- `kb_document_public`
- `kb_document_v1`

### 5.3 执行后 mapping 回读校验

6 个索引的字段类型均符合预期：

```text
source_index=keyword
index_code=keyword
owner_unit_code=keyword
visible_unit_codes=keyword
permission_version=long
```

### 5.4 幂等 dry-run 校验

再次执行：

```powershell
$env:ES_HOST='http://127.0.0.1:9200'
python ai_service/scripts/apply_document_permission_mapping.py --index kb_document_*
```

结果：

| 指标 | 数值 |
| --- | ---: |
| indices | 6 |
| with_missing | 0 |
| applied | 0 |
| conflicts | 0 |

结论：脚本可重复执行，不会重复写入已有字段。

## 6. 兼容缺陷与修复

mapping 显式追加后，重新执行普通文档回填 dry-run 时曾出现：

```text
BadRequestError(400, 'search_phase_execution_exception', 'failed to create query: empty String')
```

根因：

- `backfill_document_permission_projection.py` 原查询条件中包含 `term: ""`。
- 字段显式映射为 `keyword` 后，ES 拒绝空字符串 term 查询。
- 这是查询构造缺陷，不是 ES 服务或 mapping 写入失败。

修复：

- 回填扫描条件只保留 `must_not exists`。
- 写入脚本仍然只补 `null/空字符串/空数组` 字段，不覆盖已有非空字段。
- 当前历史数据是 5 个权限字段整体缺失，因此该修复不影响本次 691 条数据的定位。

修复后验证：

```powershell
$env:ES_HOST='http://127.0.0.1:9200'
python ai_service/scripts/backfill_document_permission_projection.py --index kb_document_* --batch-size 200 --sample 3
```

结果：

| 指标 | 数值 |
| --- | ---: |
| scanned | 691 |
| owner_from_es | 691 |
| owner_from_pg | 0 |
| owner_defaulted | 0 |
| dry_run_updates | 691 |
| written | 0 |
| failed | 0 |

## 7. 本轮结论

1. `kb_document_*` 6 个物理索引已完成权限投影字段显式 mapping 补齐。
2. 字段类型全部符合权限过滤需要。
3. mapping 补齐脚本具备 dry-run、冲突检测和幂等能力。
4. 普通文档回填脚本已修复显式 keyword mapping 下的空字符串查询兼容问题。
5. 下一步可以执行普通文档历史数据真实回填，将 691 条文档写入权限投影字段。

