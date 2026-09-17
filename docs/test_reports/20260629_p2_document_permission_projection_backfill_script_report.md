# P2 普通文档权限投影补齐脚本测试报告

## 1. 测试目标

验证 `kb_document_*` 普通文档分片历史数据是否可以离线补齐以下权限投影字段：

- `source_index`
- `index_code`
- `owner_unit_code`
- `visible_unit_codes`
- `permission_version`

本轮只完成脚本、单元测试、真实 ES dry-run 与 mapping 风险校验，未对 ES 执行真实写入。

## 2. 涉及文件

- `ai_service/scripts/backfill_document_permission_projection.py`
- `ai_service/tools_and_tests/test_backfill_document_permission_projection.py`

## 3. 单元测试结果

执行命令：

```powershell
python -m py_compile ai_service/scripts/backfill_document_permission_projection.py ai_service/tools_and_tests/test_backfill_document_permission_projection.py
python ai_service/tools_and_tests/test_backfill_document_permission_projection.py
```

结果：

- `py_compile` 通过
- 单元测试 5 个用例全部通过

覆盖范围：

- 缺失权限字段查询条件覆盖 `source_index/index_code/owner_unit_code/visible_unit_codes/permission_version`
- 优先使用 ES 现有单位字段生成 `owner_unit_code`
- ES 缺少单位字段时可从 PostgreSQL `kb_doc_registry` 补齐
- dry-run 模式不会触发 bulk 写入
- execute 模式会写回命中文档所在的真实物理索引

## 4. 真实 ES dry-run 结果

执行命令：

```powershell
$env:ES_HOST='http://127.0.0.1:9200'
python ai_service/scripts/backfill_document_permission_projection.py --index kb_document_* --batch-size 200 --sample 10
```

结果：

| 指标 | 数值 |
| --- | ---: |
| scanned | 691 |
| owner_from_es | 691 |
| owner_from_pg | 0 |
| owner_defaulted | 0 |
| skipped_non_document_index | 0 |
| dry_run_updates | 691 |
| written | 0 |
| failed | 0 |

结论：

- 当前 `kb_document_*` 下共有 691 条普通文档分片需要补齐权限投影字段。
- 这些文档现有 ES `_source` 已能提供单位来源，脚本不需要走默认 `global` 兜底。
- dry-run 未发现无法生成投影字段的文档。

## 5. 当前 mapping 校验结果

校验索引：

- `kb_document_public`
- `kb_document_official`
- `kb_document_law`
- `kb_document_notice`
- `kb_document_v1`
- `kb_document_news`

校验字段：

- `source_index`
- `index_code`
- `owner_unit_code`
- `visible_unit_codes`
- `permission_version`

结果：

| 索引 | dynamic | 权限投影字段 |
| --- | --- | --- |
| `kb_document_public` | 默认动态映射 | 5 个字段均缺失 |
| `kb_document_official` | 默认动态映射 | 5 个字段均缺失 |
| `kb_document_law` | 默认动态映射 | 5 个字段均缺失 |
| `kb_document_notice` | 默认动态映射 | 5 个字段均缺失 |
| `kb_document_v1` | 默认动态映射 | 5 个字段均缺失 |
| `kb_document_news` | 默认动态映射 | 5 个字段均缺失 |

## 6. 风险判断

不建议现在直接执行真实补齐。

原因：

1. 当前索引没有显式定义权限投影字段。
2. 如果直接 bulk update，Elasticsearch 会通过动态映射自动创建字段。
3. 动态创建后的字符串字段通常不是为权限过滤定制的最优类型，可能变成 `text + keyword` 组合，增加 mapping 噪音和存储开销。
4. 权限过滤依赖精确匹配，字段应显式定义为：
   - `source_index`: `keyword`
   - `index_code`: `keyword`
   - `owner_unit_code`: `keyword`
   - `visible_unit_codes`: `keyword`
   - `permission_version`: `long`

## 7. 建议的下一步

下一步应先为现有 `kb_document_*` 物理索引补充显式 mapping，再执行真实历史数据补齐。

建议任务顺序：

1. 使用 `_mapping` 为现有 `kb_document_*` 索引追加 5 个权限投影字段。
2. 再次执行 mapping 校验，确认字段类型全部符合预期。
3. 执行 `backfill_document_permission_projection.py --execute`。
4. 执行补齐后统计，确认 691 条文档全部具备 `source_index` 与权限字段。
5. 重新执行 Java HTTP 检索验证，确认普通文档结果可透出权限投影字段。

