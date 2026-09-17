# 2026-06-26 P2 历史 QA source_index 回填脚本单元测试报告

## 任务范围

本批任务为上一批新增的历史 QA 回填脚本补充可重复执行的测试。

新增文件：

- `ai_service/tools_and_tests/test_backfill_qa_source_index.py`

被测文件：

- `ai_service/scripts/backfill_qa_source_index.py`

本批不连接真实 ES，不执行 `--execute`，不修改历史数据。

## 测试设计

从第一性原理看，离线回填脚本的核心风险不是检索效果本身，而是字段补错、误写、覆盖错误索引。因此测试聚焦以下边界：

1. `source_index` 必须来自 chunk hit 的真实 `_index`。
2. 单位字段必须兼容现有 chunk 的 `metadata.owner_dept_id / metadata.visible_depts`。
3. 查询条件必须覆盖 `source_index` 缺失和空字符串两类历史数据。
4. dry-run 模式只能计数，不能写入。
5. execute 模式写回目标必须使用 QA hit 自身的 `_index`，不能误写读别名。

## 测试用例

### 1. 权限投影字段提取

测试方法：

- `test_projection_from_chunk_hit_prefers_physical_index_and_metadata_units`

验证点：

- `source_index == "kb_document_policy"`
- `owner_unit_code == "A001"`
- `visible_unit_codes == ["A001", "ROOT"]`
- `index_code == "policy"`

### 2. 缺失数据查询条件

测试方法：

- `test_missing_source_index_query_covers_missing_and_empty_source_index`

验证点：

- 查询覆盖 `must_not exists source_index`
- 查询覆盖 `term source_index=""`
- `minimum_should_match == 1`

### 3. dry-run 不写入

测试方法：

- `test_process_batch_dry_run_resolves_by_answer_chunk_id_without_bulk_write`

验证点：

- 能通过 `answer_chunk_id` 反查 `kb_document`。
- `resolved_by_chunk_id == 1`
- `dry_run_updates == 1`
- `written == 0`
- `failed == 0`

### 4. execute 写入目标索引正确

测试方法：

- `test_process_batch_execute_uses_hit_index_for_update`

验证点：

- bulk action 的 `_op_type == "update"`
- bulk action 的 `_index == "kb_qa_pairs_v3"`
- 不使用 `kb_qa_read` 读别名作为写入目标。
- 写入字段包含正确的 `source_index/index_code/owner_unit_code/visible_unit_codes`。

## 执行命令与结果

### Python 编译检查

```bash
python -m py_compile ai_service/scripts/backfill_qa_source_index.py ai_service/tools_and_tests/test_backfill_qa_source_index.py
```

结果：通过。

### 直接执行测试

```bash
python ai_service/tools_and_tests/test_backfill_qa_source_index.py
```

结果：通过。

输出：

```text
PASS test_projection_from_chunk_hit_prefers_physical_index_and_metadata_units
PASS test_missing_source_index_query_covers_missing_and_empty_source_index
PASS test_process_batch_dry_run_resolves_by_answer_chunk_id_without_bulk_write
PASS test_process_batch_execute_uses_hit_index_for_update
```

备注：本地 Python 环境仍输出 `RequestsDependencyWarning`，该告警来自依赖版本组合，不影响测试结果。

### pytest 执行状态

```bash
python -m pytest ai_service/tools_and_tests/test_backfill_qa_source_index.py -q
```

结果：未执行，当前环境缺少 `pytest`：

```text
No module named pytest
```

为避免引入新依赖，本批测试文件已支持直接 `python` 执行。

## 边缘案例覆盖情况

已覆盖：

- 新版 QA 可通过 `answer_chunk_id` 精确反查 chunk。
- `visible_depts` 为数组。
- `visible_depts` 为逗号分隔字符串。
- `source_index` 缺失。
- `source_index` 为空字符串。
- dry-run 不写。
- execute 写入物理 QA 索引。

未覆盖真实 ES 行为：

- `helpers.scan` scroll 行为。
- `es.mget` 对别名跨多个物理索引时的真实返回。
- `es.search` 按 `metadata.source` 兜底命中真实数据。

这些属于集成验证，应在灰度执行阶段用 `--limit` 对真实 ES 做抽样验证。

## 结论

本批单元测试已完成，脚本核心逻辑通过可重复验证。

下一步建议进入灰度执行方案：

1. 生产或预发环境执行 `--limit 100` dry-run。
2. 抽查样例中的 `source_index/index_code/owner_unit_code/visible_unit_codes`。
3. 执行 `--execute --limit 100` 小批量写入。
4. 验证 QA 检索权限过滤。
5. 全量执行 `--execute --batch-size 500`。
