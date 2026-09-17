# 2026-06-26 P2 历史 QA source_index 小批量真实回填测试报告

## 任务范围

本批任务执行历史 QA `source_index` 小批量真实回填。

被执行脚本：

- `ai_service/scripts/backfill_qa_source_index.py`

执行范围：

- `qa_index=kb_qa_read`
- `chunk_index=kb_document`
- `--execute --limit 100`

本批只写入 100 条，不执行全量回填。

## 写入前基线

执行只读统计：

```text
qa_total=419
qa_missing_source_index=419
qa_with_source_index=0
```

结论：

- 回填前共有 419 条 QA。
- 419 条全部缺少 `source_index`。

## 小批量真实回填

执行命令：

```bash
python ai_service/scripts/backfill_qa_source_index.py --execute --limit 100 --sample 20
```

执行结果：

```text
scanned=100
resolved_by_chunk_id=0
resolved_by_source=100
skipped_without_key=0
skipped_unresolved=0
dry_run_updates=0
written=100
failed=0
```

结论：

- 本批扫描 100 条。
- 100 条全部通过 `source -> metadata.source` 兜底解析。
- 成功写入 100 条。
- 写入失败 0 条。

## 写入后验证

执行只读统计：

```text
qa_total=419
qa_missing_source_index=319
qa_with_source_index=100
qa_with_permission_version=100
```

结论：

- 缺失数从 419 降到 319，符合预期。
- 已补齐数从 0 增加到 100，符合预期。
- `permission_version` 同步写入 100 条，符合预期。

## 样本字段验证

抽查写入后的 10 条样本：

```text
source=test.doc
source_index=kb_document_official
index_code=official
owner_unit_code=global
visible_unit_codes=['global']
permission_version=1782457122365
```

结论：

- `source_index` 已写入。
- `index_code` 已按 `kb_document_` 前缀裁剪为 `official`。
- 单位字段已写入。
- `permission_version` 已写入。

## 聚合验证

由于当前历史 QA 索引字段为动态 mapping，直接对 `source_index` 做 terms 聚合失败：

```text
Fielddata is disabled on [source_index] in [kb_qa_pairs].
```

只读 mapping 核对结果：

```text
kb_qa_pairs.source_index={'type': 'text', 'fields': {'keyword': {'type': 'keyword', 'ignore_above': 256}}}
kb_qa_pairs.index_code={'type': 'text', 'fields': {'keyword': {'type': 'keyword', 'ignore_above': 256}}}
kb_qa_pairs.owner_unit_code={'type': 'text', 'fields': {'keyword': {'type': 'keyword', 'ignore_above': 256}}}
kb_qa_pairs.visible_unit_codes={'type': 'text', 'fields': {'keyword': {'type': 'keyword', 'ignore_above': 256}}}
kb_qa_pairs.permission_version={'type': 'long'}
```

使用 `.keyword` 聚合后结果正常：

```text
source_index.keyword:
  kb_document_official=100

index_code.keyword:
  official=100

owner_unit_code.keyword:
  global=100
```

## 查询过滤验证

执行只读 term 查询：

```text
term source_index=kb_document_official -> 100
```

结论：

- 当前索引上 `term source_index` 可以命中已回填数据。
- 但从 mapping 角度看，现有字段是 `text + keyword`，后续更稳妥的实现应兼容 `source_index.keyword`。

## 风险与后续优化

### 已确认

- 小批量真实回填成功。
- 写入数量与缺失数量变化完全一致。
- 字段内容符合预期。
- 未出现写入失败。

### 需注意

当前历史 QA 索引的权限字段不是预期的纯 `keyword`，而是动态生成的 `text + keyword`。

影响：

- 直接 terms 聚合 `source_index` 会失败。
- 查询侧如果长期使用 `source_index` 而不是兼容 `.keyword`，在不同 mapping 版本下存在行为差异。

建议下一步优先做查询侧兼容优化：

- QA 权限过滤同时兼容 `source_index` 和 `source_index.keyword`；
- 或在正式 QA v2 索引中通过 reindex 固化为纯 `keyword` mapping；
- 在全量回填前，先确认查询侧是否需要该兼容改造。

## 本批结论

历史 QA `source_index` 小批量真实回填已完成。

结果：

- 写入前缺失：419
- 本批写入：100
- 写入后缺失：319
- 写入失败：0

建议不要立刻全量写入。下一步应先处理或确认 QA 查询侧对 `source_index.keyword` 的兼容策略，再继续全量回填。
