# 2026-06-26 P2 QA source_index.keyword 查询兼容测试报告

## 任务范围

本批任务处理小批量回填后暴露的 QA 权限字段 mapping 差异。

修改文件：

- `ai_service/main.py`
- `ai_service/tools_and_tests/test_qa_source_index_filter.py`

背景：

- 历史 `kb_qa_pairs.source_index` 是动态 mapping，实际为 `text + keyword`。
- 新模板中 `source_index` 预期为纯 `keyword`。
- 查询侧需要同时兼容两种形态。

## 实施内容

### 1. 查询字段兼容

`_readable_qa_source_index_filter` 从单字段：

```json
{"terms": {"source_index": ["kb_document_official"]}}
```

调整为双字段：

```json
{
  "bool": {
    "should": [
      {"terms": {"source_index": ["kb_document_official"]}},
      {"terms": {"source_index.keyword": ["kb_document_official"]}}
    ],
    "minimum_should_match": 1
  }
}
```

### 2. 去除缺字段放行

原逻辑包含：

```json
{"bool": {"must_not": {"exists": {"field": "source_index"}}}}
```

本批已移除。

原因：

- 当 Java 已传入 `readable_source_indexes` 时，说明正在执行角色/索引级权限约束。
- 缺少 `source_index` 的历史 QA 无法证明属于用户可读索引。
- 权限控制第一原则是“不可证明可见，则不可见”，不能默认放行。

影响：

- 剩余 319 条未回填 QA 在受限角色查询下会暂时不可见。
- 管理员或未传 `readable_source_indexes` 的路径不受该过滤影响。
- 完成剩余 319 条回填后，该临时召回缺口消失。

## 测试覆盖

新增测试：

- `test_filter_uses_source_index_and_keyword_variant`
- `test_filter_ignores_alias_or_wildcard_to_avoid_guessing`
- `test_filter_ignores_non_document_indexes`

测试文件通过 AST 只抽取 `_readable_qa_source_index_filter` 执行，避免导入整个 `main.py` 时加载模型或服务依赖。

## 执行命令与结果

### Python 编译检查

```bash
python -m py_compile ai_service/main.py ai_service/tools_and_tests/test_qa_source_index_filter.py
```

结果：通过。

### 轻量测试

```bash
python ai_service/tools_and_tests/test_qa_source_index_filter.py
```

结果：

```text
PASS test_filter_uses_source_index_and_keyword_variant
PASS test_filter_ignores_alias_or_wildcard_to_avoid_guessing
PASS test_filter_ignores_non_document_indexes
```

### 真实 ES 只读验证

执行严格双字段过滤：

```json
{
  "bool": {
    "should": [
      {"terms": {"source_index": ["kb_document_official"]}},
      {"terms": {"source_index.keyword": ["kb_document_official"]}}
    ],
    "minimum_should_match": 1
  }
}
```

结果：

```text
strict_dual_field_filter_total={'value': 100, 'relation': 'eq'}
```

结论：

- 严格过滤只命中已回填的 100 条。
- 不再放行剩余 319 条缺少 `source_index` 的历史 QA。

## 当前数据状态

只读统计：

```text
qa_missing_source_index=319
qa_with_source_index=100
```

本批没有继续写入 ES。

## 风险判断

### 已降低的风险

- 解决历史 `text + keyword` mapping 下 `.keyword` 查询兼容问题。
- 移除缺字段放行，避免受限角色看到无法证明归属的历史 QA。

### 暂存风险

- 在剩余 319 条未回填前，受限角色只能命中已回填的 100 条 QA。
- 这是安全优先的临时状态，应尽快执行剩余回填。

## 下一步建议

继续执行剩余 319 条历史 QA 回填：

```bash
python ai_service/scripts/backfill_qa_source_index.py --execute --batch-size 500
```

回填后验证：

```text
qa_missing_source_index=0
qa_with_source_index=419
```

再执行 QA 查询侧权限验证，确认不同角色的 `readable_source_indexes` 只能命中对应索引的 QA。
