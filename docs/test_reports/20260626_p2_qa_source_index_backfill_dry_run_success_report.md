# 2026-06-26 P2 历史 QA source_index 回填 dry-run 重新验证报告

## 任务范围

本报告记录 ES 恢复可达后，对历史 QA `source_index` 回填脚本重新执行 dry-run 灰度验证的结果。

被测脚本：

- `ai_service/scripts/backfill_qa_source_index.py`

本批未传入 `--execute`，未写入 ES。

## 环境连通性

执行命令：

```bash
Test-NetConnection -ComputerName localhost -Port 9200
```

结果：

```text
TcpTestSucceeded : True
```

结论：

- 当前 shell 已可访问 `localhost:9200`。
- 脚本默认配置可用于本轮 dry-run。

## 限量 dry-run 验证

执行命令：

```bash
python ai_service/scripts/backfill_qa_source_index.py --limit 100 --sample 20
```

结果：

```text
scanned=100
resolved_by_chunk_id=0
resolved_by_source=100
skipped_without_key=0
skipped_unresolved=0
dry_run_updates=100
written=0
failed=0
```

样例结论：

- QA 物理索引：`kb_qa_pairs`
- 反查得到的 chunk 物理索引：`kb_document_official`
- `index_code=official`
- `owner_unit_code=global`
- `visible_unit_codes=global`

## 只读统计验证

执行只读 count：

```text
qa_total=419
qa_missing_source_index=419
```

结论：

- 当前 `kb_qa_read` 共 419 条 QA。
- 419 条全部缺少 `source_index`，均属于本脚本回填范围。

执行只读样本查询后确认：

```text
answer_chunk_id=ee200303f52275647138898037a80e0e_fine_2
doc_version=0
```

当前主链路新版 fine chunk `_id` 格式包含版本号：

```text
{file_base_hash}_v{version}_fine_{idx}
```

因此本批历史 QA 的 `answer_chunk_id` 缺少 `_v0` 版本段，无法直接通过 chunk `_id` 命中。这解释了 dry-run 中：

```text
resolved_by_chunk_id=0
resolved_by_source=100
```

该结果符合历史数据形态，不是脚本异常。

## 全量 dry-run 验证

执行命令：

```bash
python ai_service/scripts/backfill_qa_source_index.py --sample 10
```

结果：

```text
scanned=419
resolved_by_chunk_id=0
resolved_by_source=419
skipped_without_key=0
skipped_unresolved=0
dry_run_updates=419
written=0
failed=0
```

结论：

- 全量 419 条历史 QA 均可通过 `source -> metadata.source` 兜底解析到 chunk 物理索引。
- 未发现无法解析的数据。
- 未执行任何写入。

## 告警说明

执行过程中出现两个非阻断告警：

### 1. RequestsDependencyWarning

```text
RequestsDependencyWarning: urllib3/chardet/charset_normalizer doesn't match a supported version
```

判断：

- 来自当前 Python 环境依赖版本组合。
- 不影响本轮 ES 查询和 dry-run 统计。

### 2. DeprecationWarning

```text
DeprecationWarning: The 'body' parameter is deprecated
```

判断：

- 来自当前 Elasticsearch Python client 的接口兼容提示。
- 不影响本轮脚本执行。
- 后续可单独优化脚本为新版 client 参数写法，但不应和真实数据回填混在同一任务中。

## 风险分析

### 已验证低风险点

- ES 已可达。
- `kb_qa_read` 可扫描。
- `kb_document` 读别名可用于按 `source` 兜底查询。
- 419 条历史 QA 全部可解析。
- dry-run 模式下 `written=0`。

### 需要注意的业务风险

本批历史 QA 全部通过 `source` 兜底解析，而不是通过 `answer_chunk_id` 精确解析。

原因是历史 `answer_chunk_id` 缺少版本段。对于当前数据集，全量 dry-run 未出现冲突或未解析；但如果后续生产环境存在同名 `source` 的多版本、多索引数据，`source` 兜底可能不如 chunk `_id` 精确。

建议真实写入前先执行小批量：

```bash
python ai_service/scripts/backfill_qa_source_index.py --execute --limit 100 --sample 20
```

然后抽查：

- 写入后的 `source_index`
- 写入后的 `index_code`
- 写入后的 `owner_unit_code`
- 写入后的 `visible_unit_codes`
- QA 查询侧权限过滤是否符合预期

## 下一步建议

可以进入小批量真实写入阶段，但不建议直接全量写。

推荐顺序：

1. 执行 `--execute --limit 100 --sample 20`。
2. 只读 count 验证剩余缺失数量应从 419 下降到 319。
3. 抽查已写入的 100 条字段。
4. 执行一次带权限过滤的 QA 查询验证。
5. 再执行全量 `--execute --batch-size 500`。

## 本批结论

上一轮被环境阻断的 dry-run 任务已重新执行完成。

当前结果满足进入小批量真实回填的前置条件：

- ES 可达；
- 全量 dry-run 成功；
- 419 条待回填 QA 全部可解析；
- 未发生写入；
- 未发现无法解析样本。
