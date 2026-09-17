# kb_qa_pairs_v2 全量迁移测试报告

## 一、任务目标

在不切换 `kb_qa_read/kb_qa_write` 的前提下，将旧 `kb_qa_pairs` 全量迁移到 `kb_qa_pairs_v2`，并验证 v2 索引的 mapping、文档数量、权限字段完整性。

## 二、执行命令

```powershell
python -X utf8 ai_service\scripts\migrate_qa_pairs_v2.py --execute --batch-size 500 --sample 20
```

## 三、迁移结果

```text
[QA v2 Migration] mode=execute, source=kb_qa_pairs, target=kb_qa_pairs_v2, batch_size=500, limit=all
[QA v2 Migration] target index exists: kb_qa_pairs_v2
[QA v2 Migration] alias switch skipped
[QA v2 Migration] done
  scanned=444
  dry_run_writes=0
  written=444
  failed=0
```

结论：

- 全量扫描旧索引 444 条。
- 写入 v2 444 条。
- bulk 失败 0 条。
- 未切换别名。

## 四、新旧数量对账

```text
kb_qa_pairs     444
kb_qa_pairs_v2  444
```

结论：新旧文档数一致。

## 五、Mapping 验证

`kb_qa_pairs_v2` 关键字段：

```text
acl_tokens         keyword
source_index       keyword
visible_unit_codes keyword
permission_version long
question_vector    dense_vector(1024, cosine)
```

结论：v2 已修复旧索引 `acl_tokens = text + keyword` 的 mapping 缺陷。

## 六、字段完整性验证

聚合检查结果：

```text
total=444
missing_acl_tokens=0
missing_source_index=0
missing_visible_unit_codes=0
missing_permission_version=0
question_vector_exists=444
```

`source_index` 分布：

```text
kb_document_official 401
kb_document_news      25
kb_document_public    18
```

权限查询验证：

```text
acl_tokens=_INTERNAL           419
source_index=kb_document_official 401
visible_unit_codes=global      419
```

结论：v2 权限过滤关键字段完整，可支持直接 `terms acl_tokens/source_index/visible_unit_codes`。

## 七、Alias 状态

当前别名仍指向旧索引：

```text
kb_qa_read  -> kb_qa_pairs
kb_qa_write -> kb_qa_pairs (is_write_index=true)
```

结论：本轮全量迁移没有影响线上 QA 读写路径。

## 八、测试回归

命令：

```powershell
python -X utf8 ai_service\tools_and_tests\test_migrate_qa_pairs_v2.py
```

结果：8 个用例全部通过。

## 九、下一步建议

下一步进入 alias 切换前验证：

1. 使用 `kb_qa_pairs_v2` 直接跑 QA KNN/BM25 权限查询。
2. 确认 Java/Python 侧 QA 查询对 `acl_tokens.keyword` 的兼容仍可在旧索引 alias 下工作。
3. 业务确认后，单独执行 alias 切换：

```powershell
python -X utf8 ai_service\scripts\migrate_qa_pairs_v2.py --execute --switch-alias --limit 0 --sample 0
```

切换后必须再次验证：

- `kb_qa_read -> kb_qa_pairs_v2`
- `kb_qa_write -> kb_qa_pairs_v2 (is_write_index=true)`
- QA 检索返回正常
- 旧 `kb_qa_pairs` 保留作为回滚索引
