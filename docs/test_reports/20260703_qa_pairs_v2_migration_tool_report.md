# kb_qa_pairs_v2 历史迁移工具测试报告

## 一、任务目标

本轮目标是为历史 `kb_qa_pairs` 提供安全迁移工具，解决现有索引中 `acl_tokens` 被动态映射为 `text + keyword`，无法原地改为纯 `keyword` 的问题。

本轮只新增工具与测试，不执行生产迁移，不切换别名。

## 二、实现内容

新增脚本：

- `ai_service/scripts/migrate_qa_pairs_v2.py`

核心能力：

1. 生成 `kb_qa_pairs_v2` 显式 mapping。
2. 权限字段固定为 `keyword`：
   - `acl_tokens`
   - `source_index`
   - `index_code`
   - `owner_unit_code`
   - `visible_unit_codes`
3. `permission_version` 固定为 `long`。
4. `question_vector` 保持 `dense_vector(1024, cosine)`。
5. 使用 scroll + bulk 迁移，而不是 `_reindex`。
6. 默认 dry-run，不创建索引、不写数据、不切 alias。
7. 只有显式 `--execute` 才会创建索引并写入。
8. 只有同时传入 `--switch-alias` 才会切 `kb_qa_read/kb_qa_write`。

新增测试：

- `ai_service/tools_and_tests/test_migrate_qa_pairs_v2.py`

## 三、验证命令与结果

### 1. 单元测试

命令：

```powershell
python -X utf8 ai_service\tools_and_tests\test_migrate_qa_pairs_v2.py
```

结果：8 个用例全部通过。

覆盖点：

- v2 mapping 权限字段类型正确。
- 历史字段规范化。
- 缺失 `acl_tokens` 默认写 `_NO_ACCESS`，保持 fail-closed。
- dry-run 不创建索引。
- execute 模式创建目标索引。
- dry-run 不 bulk 写入。
- execute 模式生成 bulk index action。
- alias 切换必须显式开启。

### 2. Python 语法检查

命令：

```powershell
python -m py_compile ai_service\scripts\migrate_qa_pairs_v2.py ai_service\tools_and_tests\test_migrate_qa_pairs_v2.py
```

结果：通过。

### 3. 真实 ES dry-run

命令：

```powershell
python -X utf8 ai_service\scripts\migrate_qa_pairs_v2.py --limit 2 --sample 2
```

结果：

```text
[QA v2 Migration] mode=dry-run, source=kb_qa_pairs, target=kb_qa_pairs_v2, batch_size=200, limit=2
[QA v2 Migration] dry-run create index: kb_qa_pairs_v2
[sample] qa_id=ee200303f52275647138898037a80e0e_v0_qa_2_0, acl_tokens=_INTERNAL, source_index=kb_document_official, visible_unit_codes=global
[sample] qa_id=ee200303f52275647138898037a80e0e_v0_qa_2_1, acl_tokens=_INTERNAL, source_index=kb_document_official, visible_unit_codes=global
[QA v2 Migration] alias switch skipped
[QA v2 Migration] done
  scanned=2
  dry_run_writes=2
  written=0
  failed=0
```

结论：

- dry-run 能读取历史 QA。
- 权限字段能正确保留。
- 未创建索引。
- 未写入数据。
- 未切 alias。

## 四、正式执行建议

正式迁移建议分四步执行：

1. 预演全量：

```powershell
python -X utf8 ai_service\scripts\migrate_qa_pairs_v2.py --sample 20
```

2. 小批量真实写入，不切 alias：

```powershell
python -X utf8 ai_service\scripts\migrate_qa_pairs_v2.py --execute --limit 100 --sample 20
```

3. 全量真实写入，不切 alias：

```powershell
python -X utf8 ai_service\scripts\migrate_qa_pairs_v2.py --execute --batch-size 500 --sample 20
```

4. 人工校验后切 alias：

```powershell
python -X utf8 ai_service\scripts\migrate_qa_pairs_v2.py --execute --switch-alias --limit 0 --sample 0
```

## 五、注意事项

1. 切 alias 前必须确认 `kb_qa_pairs_v2` 文档数与旧索引一致。
2. 切 alias 前必须确认 `kb_qa_pairs_v2.acl_tokens` 为 `keyword`。
3. 切 alias 前必须跑 QA 检索权限回归。
4. 当前脚本不会删除旧 `kb_qa_pairs`，旧索引用于回滚窗口保留。
