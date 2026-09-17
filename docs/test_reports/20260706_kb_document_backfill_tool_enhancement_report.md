# kb_document_* 历史权限字段补全工具增强报告

## 1. 本轮目标

在 mapping readiness 审计发现 `kb_document_*` 尚未达到目标 mapping 后，继续检查并增强历史权限字段补全工具，确保它可以用于 Docker 离线环境下的生产 dry-run 和正式执行。

## 2. 修改文件

- `ai_service/scripts/backfill_document_permission_projection.py`
- `ai_service/tools_and_tests/test_backfill_document_permission_projection.py`

## 3. 增强内容

### 3.1 参数增强

新增或补齐以下参数：

| 参数 | 说明 |
| --- | --- |
| `--indices` | `--index` 的别名，与实施文档保持一致 |
| `--es-host` | 显式指定 ES 地址 |
| `--es-user` | ES Basic Auth 用户名 |
| `--es-pass` | ES Basic Auth 密码 |
| `--output` | 输出结构化 JSON 报告 |

保留原有参数：

| 参数 | 说明 |
| --- | --- |
| `--batch-size` | 批大小 |
| `--limit` | 限制扫描数量 |
| `--sample` | 控制台样例输出数量 |
| `--execute` | 显式执行写回；不传时默认 dry-run |
| `--pg-dsn` | PostgreSQL registry 查询 DSN |
| `--no-pg` | 禁用 PG 查询 |
| `--default-owner` | 单位字段缺失时兜底值 |

### 3.2 JSON 报告

新增 `build_report`，执行结束后可输出：

- 执行模式：`dry-run` 或 `execute`
- 索引范围
- ES 地址
- 批大小
- limit
- PG 是否启用
- 起止时间
- 扫描/补全/写入/失败统计

### 3.3 ES 客户端参数化

`create_es_client` 已支持显式传入：

- `es_host`
- `es_user`
- `es_pass`

便于 Docker 离线环境中不依赖宿主默认环境变量。

## 4. 安全策略保持不变

本轮没有修改核心补全逻辑。

仍保持：

- 默认 dry-run。
- 只有显式 `--execute` 才写 ES。
- 写回目标使用 `hit._index`，不写 alias，不误写其他索引。
- 只补空字段，不覆盖已有非空字段。
- 缺失私有权限时 fail-closed 到 `_NO_ACCESS`。

## 5. 测试结果

执行命令：

```powershell
python -X utf8 ai_service\tools_and_tests\test_backfill_document_permission_projection.py
```

结果：

```text
PASS test_missing_permission_query_covers_all_projection_fields
PASS test_projection_prefers_es_owner_and_physical_index
PASS test_projection_uses_pg_registry_when_es_owner_missing
PASS test_projection_promotes_metadata_acl_tokens
PASS test_projection_fail_closed_when_private_acl_tokens_missing
PASS test_process_batch_dry_run_does_not_bulk_write
PASS test_process_batch_execute_updates_original_physical_index
PASS test_parse_args_accepts_offline_runbook_options
PASS test_build_report_contains_structured_stats
```

说明：

- 测试中出现 `RequestsDependencyWarning`，是本地 requests 依赖版本提示，不影响本脚本测试结果。

## 6. 当前未执行真实 ES dry-run 的原因

本轮环境中 ES 不可达：

```text
http://localhost:9200   Connection refused
http://127.0.0.1:9200  Connection refused
```

因此未执行真实 ES dry-run，避免输出伪结果。

## 7. ES 恢复后的 dry-run 命令

开发/验证模式：

```bash
python -X utf8 ai_service/scripts/backfill_document_permission_projection.py \
  --es-host http://localhost:9200 \
  --indices "kb_document_*" \
  --batch-size 500 \
  --limit 1000 \
  --sample 20 \
  --no-pg \
  --output docs/test_reports/kb_document_backfill_dry_run.json
```

Docker 离线环境：

```bash
cd /app/ai_service
python -X utf8 scripts/backfill_document_permission_projection.py \
  --es-host "$ES_HOST" \
  --indices "kb_document_*" \
  --batch-size 500 \
  --limit 1000 \
  --sample 20 \
  --output /tmp/kb_document_backfill_dry_run.json
```

如果 ES 有认证：

```bash
python -X utf8 scripts/backfill_document_permission_projection.py \
  --es-host "$ES_HOST" \
  --es-user "$ES_USER" \
  --es-pass "$ES_PASS" \
  --indices "kb_document_*" \
  --batch-size 500 \
  --limit 1000 \
  --sample 20 \
  --output /tmp/kb_document_backfill_dry_run.json
```

## 8. 正式执行命令

小批量执行：

```bash
python -X utf8 scripts/backfill_document_permission_projection.py \
  --es-host "$ES_HOST" \
  --indices "kb_document_*" \
  --batch-size 200 \
  --limit 1000 \
  --sample 20 \
  --execute \
  --output /tmp/kb_document_backfill_batch1.json
```

全量执行：

```bash
python -X utf8 scripts/backfill_document_permission_projection.py \
  --es-host "$ES_HOST" \
  --indices "kb_document_*" \
  --batch-size 500 \
  --sample 20 \
  --execute \
  --output /tmp/kb_document_backfill_full.json
```

## 9. 下一步建议

ES 恢复后，下一步应执行：

1. `audit_es_mapping_readiness.py` 在线审计。
2. `backfill_document_permission_projection.py` dry-run。
3. 审核 dry-run 样例和 JSON 报告。
4. 小批量 `--execute`。
5. 再次运行权限字段缺失审计。

如果补全后仍需要纯净目标 mapping，再进入 `migrate_kb_document_index_v2.py` 的实现。
