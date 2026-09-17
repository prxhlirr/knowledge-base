# kb_qa_pairs_v2 小批量真实迁移测试报告

## 一、任务目标

验证 `kb_qa_pairs_v2` 历史迁移工具在真实 ES 上可以安全创建目标索引并写入小批量数据，同时不切换 `kb_qa_read/kb_qa_write` 生产别名。

## 二、执行命令

```powershell
python -X utf8 ai_service\scripts\migrate_qa_pairs_v2.py --execute --limit 100 --sample 20
```

## 三、执行结果

```text
[QA v2 Migration] mode=execute, source=kb_qa_pairs, target=kb_qa_pairs_v2, batch_size=200, limit=100
[QA v2 Migration] created index: kb_qa_pairs_v2
[QA v2 Migration] alias switch skipped
[QA v2 Migration] done
  scanned=100
  dry_run_writes=0
  written=100
  failed=0
```

结论：

- 成功创建 `kb_qa_pairs_v2`。
- 扫描旧索引 `kb_qa_pairs` 100 条。
- 写入 `kb_qa_pairs_v2` 100 条。
- bulk 失败 0 条。
- 未切换别名。

## 四、Mapping 验证

读取 `kb_qa_pairs_v2/_mapping` 后确认：

```text
acl_tokens         keyword
source_index       keyword
index_code         keyword
owner_unit_code    keyword
visible_unit_codes keyword
permission_version long
question_vector    dense_vector(1024, cosine)
```

这说明 v2 索引已解决旧索引 `acl_tokens = text + keyword` 的动态映射问题。

## 五、数据验证

计数：

```text
kb_qa_pairs     444
kb_qa_pairs_v2  100
```

权限字段查询：

```text
kb_qa_pairs_v2 terms acl_tokens   -> 100
kb_qa_pairs_v2 terms source_index -> 100
```

`.keyword` 子字段查询：

```text
kb_qa_pairs_v2 terms acl_tokens.keyword   -> 0
kb_qa_pairs_v2 terms source_index.keyword -> 0
```

解释：v2 中字段已是纯 `keyword`，不再需要 `.keyword` 子字段；查询应直接使用 `acl_tokens/source_index`。

## 六、Alias 验证

当前别名仍指向旧索引：

```text
kb_qa_read  -> kb_qa_pairs
kb_qa_write -> kb_qa_pairs (is_write_index=true)
```

结论：本次小批量迁移未影响线上 QA 读写路径。

## 七、测试回归

命令：

```powershell
python -X utf8 ai_service\tools_and_tests\test_migrate_qa_pairs_v2.py
```

结果：8 个用例全部通过。

## 八、下一步建议

下一步可以执行全量迁移，但仍不切别名：

```powershell
python -X utf8 ai_service\scripts\migrate_qa_pairs_v2.py --execute --batch-size 500 --sample 20
```

全量迁移后再验证：

1. `kb_qa_pairs_v2` 文档数是否等于 `kb_qa_pairs`。
2. `acl_tokens/source_index/visible_unit_codes` 缺失数是否为 0。
3. QA KNN/BM25 权限查询是否命中预期。
4. 验证通过后，最后单独执行 alias 切换。
