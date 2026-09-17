# 2026-06-26 P2 历史 QA source_index 回填脚本测试报告

## 任务范围

本批任务只新增离线回填脚本，不改在线检索链路。

新增文件：

- `ai_service/scripts/backfill_qa_source_index.py`

目标：

- 为历史 `kb_qa_pairs` / `kb_qa_read` 中缺少 `source_index` 或 `source_index=""` 的 QA 文档补齐权限投影字段。
- 补齐字段限定为：`source_index`、`index_code`、`owner_unit_code`、`visible_unit_codes`、`permission_version`。
- 默认 dry-run，必须显式传入 `--execute` 才真实写 ES。

## 代码验证结论

### 1. source_index 来源验证

已通过代码检索确认：

- 当前主链路 fine chunk `_id` 格式：
  - `ai_service/core/rag_pipeline.py`
  - `doc_id = f"{file_base_hash}_v{new_version}_fine_{original_i}"`
- 当前主链路 QA `answer_chunk_id` 格式：
  - `ai_service/core/rag_pipeline.py`
  - `answer_chunk_id = f"{file_base_hash}_v{doc_version}_fine_{chunk_idx}"`

因此，新版历史数据可通过 `answer_chunk_id -> kb_document 读别名 -> hit._index` 精确反查真实物理索引。

同时也确认旧版脚本存在不带版本号的 QA `answer_chunk_id`：

- `ai_service/scripts/rag_pipeline.py`
- `answer_chunk_id = f"{file_base_hash}_fine_{idx}"`

所以脚本保留 `source -> metadata.source` 的兜底查询路径，并对无法解析的数据输出 `skipped_unresolved` 计数，避免静默误补。

### 2. 写入字段边界验证

脚本只构造最小更新字段：

- `source_index`
- `index_code`
- `owner_unit_code`
- `visible_unit_codes`
- `permission_version`

不会修改：

- `question`
- `answer`
- `question_vector`
- `answer_chunk_id`
- `doc_hash`
- `doc_version`
- `source`

### 3. 安全开关验证

脚本默认模式为 `dry-run`。

真实写入必须显式执行：

```bash
python ai_service/scripts/backfill_qa_source_index.py --execute
```

灰度建议先执行：

```bash
python ai_service/scripts/backfill_qa_source_index.py --limit 100
```

确认样例输出无误后再加 `--execute`。

## 执行过的验证命令

### Python 编译检查

```bash
python -m py_compile ai_service/scripts/backfill_qa_source_index.py
```

结果：通过。

### 参数解析检查

```bash
python ai_service/scripts/backfill_qa_source_index.py --help
```

结果：通过，参数可正常输出。

备注：本地 Python 环境输出了 `RequestsDependencyWarning`，提示 `requests/urllib3/chardet` 版本组合不完全匹配。该告警来自环境依赖，不影响本脚本的语法、参数解析和 ES 客户端创建逻辑。

### 关键字段静态核对

```bash
rg -n "source_index|index_code|owner_unit_code|visible_unit_codes|permission_version|--execute|dry-run|answer_chunk_id|metadata.source" ai_service/scripts/backfill_qa_source_index.py
```

结果：通过，字段、dry-run 开关、反查路径均存在。

### chunk 与 QA ID 对齐核对

```bash
rg -n "doc_id =|file_base_hash.*fine|answer_chunk_id" ai_service/core/rag_pipeline.py ai_service/scripts/rag_pipeline.py
```

结果：通过，确认新版主链路对齐，旧版脚本存在历史不一致，因此兜底路径必要。

## 未执行项说明

未在本批直接执行 `--execute`，原因：

- 该操作会写入真实 ES 历史数据；
- 用户前置要求数据库和历史数据操作应可控、可审计；
- 当前批次目标是交付可灰度、默认安全的离线脚本。

建议生产执行顺序：

1. `python ai_service/scripts/backfill_qa_source_index.py --limit 100`
2. 抽查样例中的 `source_index/index_code/owner_unit_code/visible_unit_codes`
3. `python ai_service/scripts/backfill_qa_source_index.py --execute --limit 100`
4. 确认 QA 检索权限过滤正常
5. `python ai_service/scripts/backfill_qa_source_index.py --execute --batch-size 500`

## 边缘案例与测试建议

建议覆盖以下数据样本：

- QA 缺少 `source_index`，且 `answer_chunk_id` 可命中 chunk。
- QA 缺少 `source_index`，`answer_chunk_id` 不可命中，但 `source` 可命中 chunk。
- QA 缺少 `source_index`，`answer_chunk_id/source` 都不可命中，应进入 `skipped_unresolved`。
- QA 已存在 `source_index`，脚本不应覆盖。
- chunk 缺少 `visible_depts`，脚本应使用 `owner_dept_id` 兜底为 `visible_unit_codes`。
- QA 读别名指向物理索引时，脚本应使用 hit `_index` 原地更新。

## 结论

本批 P2 历史 QA `source_index` 回填脚本已完成。

脚本具备以下特性：

- 权限字段补齐来源经过现有代码验证；
- 默认 dry-run，正式写入需显式确认；
- 对新版数据优先精确反查；
- 对旧版历史数据保留兜底和失败计数；
- 不修改 QA 内容、向量和版本字段。
