# kb_document_* Mapping Readiness 工具实施报告

## 1. 本轮目标

实现一个可在 Docker 离线环境使用的 mapping readiness 审计工具，用于判断 `kb_document_*` 是否满足目标 mapping 与权限控制前置要求。

## 2. 新增文件

- `ai_service/scripts/audit_es_mapping_readiness.py`
- `ai_service/tools_and_tests/test_audit_es_mapping_readiness.py`

## 3. 工具能力

`audit_es_mapping_readiness.py` 支持两种模式：

### 3.1 在线 ES 审计

```bash
python -X utf8 ai_service/scripts/audit_es_mapping_readiness.py \
  --es-host "$ES_HOST" \
  --indices "kb_document_*" \
  --output /tmp/kb_document_mapping_readiness.json
```

### 3.2 离线导出文件审计

```bash
python -X utf8 ai_service/scripts/audit_es_mapping_readiness.py \
  --indices "kb_document_*" \
  --from-export-dir docs/es_exports/20260703_mapping_audit/raw \
  --output /tmp/kb_document_mapping_readiness.json
```

这个模式不访问 ES，适合 Docker 离线环境或生产导出包审批。

### 3.3 生产模式

```bash
python -X utf8 ai_service/scripts/audit_es_mapping_readiness.py \
  --indices "kb_document_*" \
  --from-export-dir docs/es_exports/20260703_mapping_audit/raw \
  --production \
  --fail-on-risk
```

生产模式下：

- `number_of_replicas=0` 视为 error。
- `number_of_shards=1` 视为 warning，因为是否需要多分片取决于容量规划。

## 4. 审计字段

工具会检查以下目标字段：

| 字段 | 目标 |
| --- | --- |
| `content` | `text`, `ik_max_word`, `search_analyzer=ik_smart` |
| `display_content` | `text` |
| `vector` | `dense_vector`, `dims=1024`, `similarity=cosine`, `index=true` |
| `sparse_vector` | `rank_features` |
| `acl_tokens` | `keyword` |
| `source_index` | `keyword` |
| `index_code` | `keyword` |
| `owner_unit_code` | `keyword` |
| `visible_unit_codes` | `keyword` |
| `permission_version` | `long` |
| `metadata.is_latest` | `boolean` |
| `metadata.doc_version` | `integer` |
| `metadata.acl_tokens` | `keyword`，可选 |
| `metadata.source_index` | `keyword`，可选 |
| `metadata.visible_unit_codes` | `keyword`，可选 |
| `metadata.permission_version` | `long`，可选 |

说明：

- 主索引当前检索链路使用 `metadata.is_latest` 与 `metadata.doc_version`，所以工具按真实代码路径审计，不强制根级 `is_latest/doc_version`。

## 5. 单元测试

执行命令：

```powershell
python -X utf8 ai_service\tools_and_tests\test_audit_es_mapping_readiness.py
```

结果：

```text
PASS test_valid_mapping_is_ready
PASS test_acl_tokens_must_be_keyword
PASS test_metadata_latest_and_doc_version_are_checked
PASS test_zero_replica_is_error_only_in_production
PASS test_has_risk_tracks_not_ready_indices
PASS test_run_audit_from_export_reads_mapping_and_settings
```

## 6. 本地 ES 连通性

本轮尝试访问：

- `http://localhost:9200`
- `http://127.0.0.1:9200`

结果均为连接拒绝。

因此本轮真实审计改用之前导出的 mapping/settings 文件执行离线审计。

## 7. 离线审计结果

输入目录：

- `docs/es_exports/20260703_mapping_audit/raw`

输出文件：

- `docs/test_reports/20260706_kb_document_mapping_readiness_offline_dev.json`
- `docs/test_reports/20260706_kb_document_mapping_readiness_offline_prod.json`

### 7.1 开发模式摘要

| 指标 | 值 |
| --- | ---: |
| total | `6` |
| ready | `0` |
| notReady | `6` |
| errorCount | `10` |
| warningCount | `12` |

主要问题：

| 索引 | 错误 |
| --- | --- |
| `kb_document_law` | `acl_tokens=text`, `metadata.source_index=text`, `metadata.visible_unit_codes=text` |
| `kb_document_news` | `acl_tokens=text`, `metadata.source_index=text`, `metadata.visible_unit_codes=text` |
| `kb_document_notice` | 缺少根级 `acl_tokens` |
| `kb_document_official` | `acl_tokens=text` |
| `kb_document_public` | `acl_tokens=text` |
| `kb_document_v1` | 缺少根级 `acl_tokens` |

所有索引均有：

- `number_of_shards=1`
- `number_of_replicas=0`

开发模式中 replicas=0 只作为 warning。

### 7.2 生产模式摘要

| 指标 | 值 |
| --- | ---: |
| total | `6` |
| ready | `0` |
| notReady | `6` |
| errorCount | `16` |
| warningCount | `6` |

生产模式中，`number_of_replicas=0` 也被视为 error。

## 8. 结论

当前导出的 `kb_document_*` 物理索引没有一个满足目标 mapping readiness。

因此，后续决策必须分两条：

1. **短期兼容运行**
   - 查询侧继续兼容 `acl_tokens.keyword`。
   - 历史数据先做权限字段补全。
   - 不立刻迁移全部主索引。

2. **生产目标 mapping**
   - 必须逐索引迁移到 v2。
   - 新索引中 `acl_tokens/source_index/index_code/owner_unit_code/visible_unit_codes` 应为纯 `keyword`。
   - 生产环境副本数至少为 1。
   - 分片数需要根据数据量重新规划。

## 9. 下一步建议

下一步应实现并执行：

- `backfill_document_permission_projection.py` 的生产 dry-run。
- 新入库样例门禁验证。

如果业务决定进入目标 mapping 迁移，再继续实现：

- `migrate_kb_document_index_v2.py`

不建议在 mapping readiness 未通过的情况下直接声明“只补历史数据即可”。
