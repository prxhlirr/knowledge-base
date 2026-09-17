# 2026-06-26 P2 历史 QA source_index 剩余数据全量回填测试报告

## 任务范围

本批任务执行剩余历史 QA `source_index` 全量回填。

被执行脚本：

- `ai_service/scripts/backfill_qa_source_index.py`

执行范围：

- `qa_index=kb_qa_read`
- `chunk_index=kb_document`
- `--execute --batch-size 500`

本批承接上一轮小批量回填后的状态，只处理剩余缺少 `source_index` 的 QA 文档。

## 写入前基线

执行只读统计：

```text
qa_total=419
qa_missing_source_index=319
qa_with_source_index=100
```

结论：

- 历史 QA 总数 419。
- 上一批已补齐 100。
- 本批待回填 319。

## 剩余数据真实回填

执行命令：

```bash
python ai_service/scripts/backfill_qa_source_index.py --execute --batch-size 500 --sample 20
```

执行结果：

```text
scanned=319
resolved_by_chunk_id=0
resolved_by_source=319
skipped_without_key=0
skipped_unresolved=0
dry_run_updates=0
written=319
failed=0
```

结论：

- 剩余 319 条全部扫描到。
- 319 条全部通过 `source -> metadata.source` 兜底解析。
- 成功写入 319 条。
- 写入失败 0 条。

## 写入后计数验证

执行只读统计：

```text
qa_total=419
qa_missing_source_index=0
qa_with_source_index=419
qa_with_permission_version=419
```

结论：

- 所有历史 QA 均已补齐 `source_index`。
- 所有历史 QA 均已补齐 `permission_version`。
- 不再存在缺少 `source_index` 的 QA 文档。

## 字段分布验证

使用 `.keyword` 聚合统计：

```text
source_index.keyword:
  kb_document_official=401
  kb_document_public=18

index_code.keyword:
  official=401
  public=18

owner_unit_code.keyword:
  global=419
```

结论：

- 回填不是一刀切写入同一索引。
- 脚本根据历史 QA 的 `source` 反查到了不同的 chunk 物理索引。
- 当前历史 QA 分布为：
  - `kb_document_official`：401 条
  - `kb_document_public`：18 条

## 样本验证

### official 样本

```text
source=test.doc
source_index=kb_document_official
index_code=official
owner_unit_code=global
visible_unit_codes=['global']
```

### public 样本

```text
source=test_gongshi.docx
source_index=kb_document_public
index_code=public
owner_unit_code=global
visible_unit_codes=['global']
```

结论：

- `source_index` 与 `index_code` 对齐。
- 单位字段已写入。
- `permission_version` 已写入。

## 查询侧严格过滤验证

### 单索引过滤

执行严格双字段过滤：

```json
{
  "should": [
    {"terms": {"source_index": ["kb_document_official"]}},
    {"terms": {"source_index.keyword": ["kb_document_official"]}}
  ],
  "minimum_should_match": 1
}
```

结果：

```text
strict_dual_field_filter_total={'value': 401, 'relation': 'eq'}
```

### 多索引过滤

执行严格双字段过滤：

```json
{
  "should": [
    {"terms": {"source_index": ["kb_document_official", "kb_document_public"]}},
    {"terms": {"source_index.keyword": ["kb_document_official", "kb_document_public"]}}
  ],
  "minimum_should_match": 1
}
```

结果：

```text
strict_dual_field_filter_official_public_total={'value': 419, 'relation': 'eq'}
```

结论：

- 查询侧严格过滤已经可以准确区分 `official` 和 `public`。
- 多索引角色可命中全部 419 条。
- 单索引角色只命中其可读索引对应 QA。

## 自动化测试

执行命令：

```bash
python ai_service/tools_and_tests/test_qa_source_index_filter.py
```

结果：

```text
PASS test_filter_uses_source_index_and_keyword_variant
PASS test_filter_ignores_alias_or_wildcard_to_avoid_guessing
PASS test_filter_ignores_non_document_indexes
```

## 告警说明

执行过程中仍有两个非阻断告警：

```text
RequestsDependencyWarning
DeprecationWarning: The 'body' parameter is deprecated
```

判断：

- `RequestsDependencyWarning` 来自当前 Python 依赖版本组合。
- `DeprecationWarning` 来自 Elasticsearch Python client 参数形式。
- 二者不影响本次写入和查询验证结果。

## 本批结论

历史 QA `source_index` 回填已全部完成。

最终状态：

```text
qa_total=419
qa_missing_source_index=0
qa_with_source_index=419
qa_with_permission_version=419
```

权限索引分布：

```text
kb_document_official=401
kb_document_public=18
```

下一步建议进入 QA 权限查询端到端验证：

- 使用只读 `official` 的角色查询 QA，应只能命中 `kb_document_official`。
- 使用只读 `public` 的角色查询 QA，应只能命中 `kb_document_public`。
- 使用同时可读 `official,public` 的角色查询 QA，应能命中全部相关 QA。
