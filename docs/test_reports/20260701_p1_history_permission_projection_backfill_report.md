# P1 历史权限投影补齐测试报告

## 任务范围

- 修复 `kb_document_*` 历史 chunk 缺少根级 `acl_tokens` 的问题。
- 修复 `kb_doc_search` / `kb_doc_meta` 历史文档级辅助索引缺少 `source_index`、`visible_unit_codes` 的问题。
- 补齐后复跑权限投影就绪审计，验证是否具备关闭 legacy 兼容分支的条件。

## 代码变更

- `ai_service/scripts/backfill_document_permission_projection.py`
  - 将 `acl_tokens` 纳入 chunk 权限投影补齐字段。
  - 优先复用根级 `acl_tokens` 或 `metadata.acl_tokens`。
  - 当 token 完全缺失时，按 `visibility` 做保守兜底：`PUBLIC -> _PUBLIC`，`INTERNAL/缺省 -> _INTERNAL`，其它无法证明授权关系的类型 -> `_NO_ACCESS`。
- `ai_service/scripts/backfill_auxiliary_permission_projection.py`
  - 新增文档级辅助索引权限投影补齐脚本。
  - 通过 `source/source_name` 反查 `kb_document` 最新 chunk 的真实 `_index` 和单位投影。
  - 仅填充空字段，不覆盖已有非空投影。
- `ai_service/tools_and_tests/test_backfill_document_permission_projection.py`
  - 增加 `metadata.acl_tokens` 提升到根级、私密缺 token fail-closed 的测试。
- `ai_service/tools_and_tests/test_backfill_auxiliary_permission_projection.py`
  - 增加辅助索引缺字段查询、source 反查、最小更新文档、物理索引原地更新测试。

## 执行记录

### 1. chunk 补齐脚本单测

命令：

```powershell
python -m py_compile ai_service\scripts\backfill_document_permission_projection.py ai_service\tools_and_tests\test_backfill_document_permission_projection.py
python ai_service\tools_and_tests\test_backfill_document_permission_projection.py
```

结果：

- 编译通过。
- 7 个用例全部通过。
- 存在本地环境已有 `RequestsDependencyWarning`，不影响脚本执行。

### 2. chunk dry-run

命令：

```powershell
python ai_service\scripts\backfill_document_permission_projection.py --limit 10 --sample 10 --no-pg
```

结果：

- scanned=10
- dry_run_updates=10
- written=0
- failed=0
- 样本显示根级 `acl_tokens` 将由 `metadata.acl_tokens` 补为 `_INTERNAL`，未扩大权限。

### 3. chunk 正式补齐

命令：

```powershell
python ai_service\scripts\backfill_document_permission_projection.py --execute --batch-size 200 --sample 5 --no-pg
```

结果：

- scanned=595
- owner_from_es=595
- owner_from_pg=0
- owner_defaulted=0
- written=595
- failed=0

### 4. 辅助索引补齐脚本单测

命令：

```powershell
python -m py_compile ai_service\scripts\backfill_auxiliary_permission_projection.py ai_service\tools_and_tests\test_backfill_auxiliary_permission_projection.py
python ai_service\tools_and_tests\test_backfill_auxiliary_permission_projection.py
```

结果：

- 编译通过。
- 4 个用例全部通过。
- 存在本地环境已有 `RequestsDependencyWarning`，不影响脚本执行。

### 5. 辅助索引 dry-run

命令：

```powershell
python ai_service\scripts\backfill_auxiliary_permission_projection.py --limit 30 --sample 20
```

结果：

- `kb_doc_search`
  - scanned=19
  - resolved_by_source=19
  - skipped_without_source=0
  - skipped_unresolved=0
  - dry_run_updates=19
- `kb_doc_meta`
  - scanned=13
  - resolved_by_source=13
  - skipped_without_source=0
  - skipped_unresolved=0
  - dry_run_updates=13

### 6. 辅助索引正式补齐

命令：

```powershell
python ai_service\scripts\backfill_auxiliary_permission_projection.py --execute --batch-size 100 --sample 5
```

结果：

- `kb_doc_search`
  - scanned=19
  - resolved_by_source=19
  - written=19
  - failed=0
- `kb_doc_meta`
  - scanned=13
  - resolved_by_source=13
  - written=13
  - failed=0

### 7. 权限投影 readiness 审计

命令：

```powershell
python ai_service\scripts\audit_permission_projection_readiness.py --fail-on-risk
```

结果：

```json
{
  "chunk": {
    "index": "kb_document_*",
    "latestField": "metadata.is_latest",
    "missing": {
      "acl_tokens": 0,
      "source_index": 0,
      "visible_unit_codes": 0
    }
  },
  "doc_search": {
    "index": "kb_doc_search",
    "latestField": "is_latest",
    "missing": {
      "acl_tokens": 0,
      "source_index": 0,
      "visible_unit_codes": 0
    }
  },
  "doc_meta": {
    "index": "kb_doc_meta",
    "latestField": "is_latest",
    "missing": {
      "acl_tokens": 0,
      "source_index": 0,
      "visible_unit_codes": 0
    }
  },
  "qa": {
    "index": "kb_qa_*",
    "latestField": "is_latest",
    "missing": {
      "acl_tokens": 0,
      "source_index": 0,
      "visible_unit_codes": 0
    }
  }
}
```

审计结论：四类检索索引的 latest 数据权限投影缺口已归零，`--fail-on-risk` 成功返回。

## 边缘案例与后续测试建议

- `PRIVATE/GRANT` 历史 chunk 如果缺少可证明的 `acl_tokens`，脚本会补 `_NO_ACCESS`，建议后续通过数据库授权表单独重建这些文档的授权 token。
- source 名称污染或乱码文档已经可以通过 `source/source_name` 反查成功，本次 dry-run 未发现 unresolved；若生产数据存在 unresolved，应先修复 source 映射再补齐。
- 建议下一步在测试环境关闭 legacy missing-field 兼容开关后，执行管理员、普通内部用户、部门上级用户、角色索引授权用户四类检索回归。
