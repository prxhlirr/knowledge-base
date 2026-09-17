# Docker 离线环境 ES 数据补全与迁移方案

## 1. 方案目标

在 AI 服务通过 Docker 部署、生产环境可能无法联网的前提下，完成 ES 索引权限字段补全、必要索引迁移、结果校验和回滚准备。

适用范围：

- `kb_document_*`
- `kb_doc_search_v1`
- `kb_doc_meta_v2`
- `kb_qa_pairs_v2`

本方案默认不依赖外网。

## 2. 核心原则

1. 能原索引补字段的，不新建索引。
2. 已存在字段类型错误的，不尝试原地修改。
3. 历史数据补全必须先 dry-run，再 execute。
4. 所有迁移前必须导出 mapping/settings/alias 状态。
5. 所有写操作必须可回滚或可重复执行。
6. 生产切换必须使用 alias，不让业务代码直接绑定 vN 物理索引。

## 3. 离线包准备

建议在可访问代码仓库的机器上准备一个离线包：

```text
offline_es_migration_package/
  ai_service/
    scripts/
    core/
    tools_and_tests/
  docs/
    es_exports/
    *.md
  requirements.lock.txt
  runbooks/
```

至少包含：

- `ai_service/scripts/backfill_document_permission_projection.py`
- `ai_service/scripts/backfill_auxiliary_permission_projection.py`
- `ai_service/scripts/audit_permission_projection_readiness.py`
- `ai_service/scripts/migrate_qa_pairs_v2.py`
- `ai_service/core/permissions/`
- `docs/es_exports/20260703_mapping_audit/`
- 本方案文档

## 4. Docker 环境前置确认

进入 AI 服务容器：

```bash
docker exec -it <ai_service_container> bash
```

确认环境变量：

```bash
env | grep -E 'ES_HOST|ES_USER|ES_PASS|ES_USERNAME|ES_PASSWORD|PYTHONPATH'
```

确认 ES 连通：

```bash
python - <<'PY'
import os, urllib.request
es = os.getenv("ES_HOST", "http://elasticsearch:9200")
with urllib.request.urlopen(es + "/_cluster/health", timeout=10) as resp:
    print(resp.read().decode())
PY
```

确认 Python 依赖：

```bash
python - <<'PY'
import elasticsearch
print("elasticsearch client ok")
PY
```

## 5. 执行前备份

### 5.1 导出 alias

```bash
curl -s "$ES_HOST/_cat/aliases/kb_doc*,kb_document*,kb_qa*?h=alias,index,is_write_index&format=json" \
  > /tmp/es_aliases_before.json
```

### 5.2 导出 mapping

```bash
curl -s "$ES_HOST/_cat/indices/kb_doc*,kb_document*,kb_qa*?h=index&format=json" > /tmp/es_indices.json
```

逐个导出：

```bash
python - <<'PY'
import json, os, urllib.request
from pathlib import Path
es = os.getenv("ES_HOST", "http://elasticsearch:9200")
out = Path("/tmp/es_mapping_backup")
out.mkdir(parents=True, exist_ok=True)
indices = json.loads(urllib.request.urlopen(es + "/_cat/indices/kb_doc*,kb_document*,kb_qa*?h=index&format=json").read().decode())
for row in indices:
    idx = row["index"]
    for suffix in ("_mapping", "_settings"):
        with urllib.request.urlopen(es + f"/{idx}/{suffix}") as resp:
            (out / f"{idx}.{suffix[1:]}.json").write_text(resp.read().decode(), encoding="utf-8")
print(out)
PY
```

### 5.3 Snapshot 建议

如果生产 ES 已配置 snapshot repository，补全前建议执行 snapshot。

如果未配置 snapshot，至少保留：

- mapping/settings 导出
- alias 导出
- 待执行脚本版本
- dry-run 报告

## 6. 阶段一：权限字段缺失审计

执行审计脚本：

```bash
cd /app/ai_service
python -X utf8 scripts/audit_permission_projection_readiness.py \
  --es-host "$ES_HOST" \
  --output /tmp/permission_audit_before.json
```

重点看：

- `acl_tokens`
- `source_index`
- `index_code`
- `owner_unit_code`
- `visible_unit_codes`
- `permission_version`

验收标准：

- 明确每个索引缺失数量。
- 明确是否存在空字符串字段。
- 明确是否存在旧索引误写。

## 7. 阶段二：主文档索引补全

适用对象：

- `kb_document_public`
- `kb_document_official`
- `kb_document_news`
- `kb_document_law`
- `kb_document_notice`
- `kb_document_v1`

### 7.1 dry-run

```bash
cd /app/ai_service
python -X utf8 scripts/backfill_document_permission_projection.py \
  --es-host "$ES_HOST" \
  --indices "kb_document_*" \
  --batch-size 500 \
  --limit 1000 \
  --dry-run \
  --output /tmp/backfill_document_dry_run.json
```

检查：

- 推导出的 `source_index` 是否等于真实物理索引。
- `visible_unit_codes` 是否来自数据库/metadata/单位字段。
- 缺失私有权限时是否按 `_NO_ACCESS` fail-closed。
- 公开数据是否符合 `_INTERNAL` 或既定公开 token 规则。

### 7.2 小批量执行

```bash
python -X utf8 scripts/backfill_document_permission_projection.py \
  --es-host "$ES_HOST" \
  --indices "kb_document_*" \
  --batch-size 200 \
  --limit 1000 \
  --execute \
  --output /tmp/backfill_document_batch1.json
```

### 7.3 全量执行

```bash
python -X utf8 scripts/backfill_document_permission_projection.py \
  --es-host "$ES_HOST" \
  --indices "kb_document_*" \
  --batch-size 500 \
  --execute \
  --output /tmp/backfill_document_full.json
```

### 7.4 校验

```bash
python -X utf8 scripts/audit_permission_projection_readiness.py \
  --es-host "$ES_HOST" \
  --output /tmp/permission_audit_after_document.json
```

验收标准：

- 主文档索引关键权限字段缺失数为 0。
- 无权限 token 查询不返回私有文档。
- 授权 token 查询可返回对应文档。

## 8. 阶段三：辅助索引补全

适用对象：

- `kb_doc_search_v1`
- `kb_doc_meta_v2`

### 8.1 dry-run

```bash
python -X utf8 scripts/backfill_auxiliary_permission_projection.py \
  --es-host "$ES_HOST" \
  --indices "kb_doc_search_v1,kb_doc_meta_v2" \
  --batch-size 500 \
  --limit 1000 \
  --dry-run \
  --output /tmp/backfill_auxiliary_dry_run.json
```

### 8.2 执行

```bash
python -X utf8 scripts/backfill_auxiliary_permission_projection.py \
  --es-host "$ES_HOST" \
  --indices "kb_doc_search_v1,kb_doc_meta_v2" \
  --batch-size 500 \
  --execute \
  --output /tmp/backfill_auxiliary_full.json
```

### 8.3 校验

```bash
python -X utf8 scripts/audit_permission_projection_readiness.py \
  --es-host "$ES_HOST" \
  --output /tmp/permission_audit_after_auxiliary.json
```

验收标准：

- `kb_doc_search_v1` 权限字段缺失数为 0。
- `kb_doc_meta_v2` 权限字段缺失数为 0。
- 与主文档同一 `doc_hash/content_hash/source` 的权限字段一致。

## 9. 阶段四：是否迁移 `kb_doc_meta_v2`

当前 `kb_doc_meta_v2` 的以下字段是 `text + keyword`：

- `source_index`
- `index_code`
- `owner_unit_code`
- `visible_unit_codes`

如果生产查询只是展示或低频管理查询：

- 暂不迁移。
- 查询使用 `.keyword`。

如果生产查询需要高频按这些字段 filter/aggregation：

- 建议新建 `kb_doc_meta_v3`。
- 将上述字段改为纯 `keyword`。
- 使用 `kb_doc_meta_read/kb_doc_meta_write` alias 切换。

迁移流程：

1. 创建 `kb_doc_meta_v3`。
2. 从 `kb_doc_meta_v2` reindex 或 bulk migrate。
3. 校验 count。
4. 校验字段缺失。
5. 切换 alias。
6. 保留 `kb_doc_meta_v2` 观察期。

## 10. 阶段五：主文档索引是否迁移

当前不建议立即迁移全部 `kb_document_*`。

原因：

- 主文档索引是核心检索路径。
- 多个业务索引共用 `kb_document` 读 alias。
- 当前主要问题是历史字段类型不统一，而不是不可用。
- 大规模迁移会带来停机、双写、回滚和一致性风险。

推荐策略：

| 场景 | 方案 |
| --- | --- |
| 只缺权限字段 | 原索引 backfill |
| 字段类型错误但代码兼容 `.keyword` | 暂缓迁移 |
| analyzer/vector/shard 必须调整 | 新建业务索引 v2 并逐个迁移 |
| 空索引 | 可直接删除重建或保留 template 自动创建 |

逐个业务索引迁移时，不要一次性迁移全部 `kb_document_*`。

建议顺序：

1. 空索引：`kb_document_law/kb_document_notice/kb_document_v1`
2. 小索引：`kb_document_news/kb_document_public`
3. 大索引：`kb_document_official`

## 11. 阶段六：QA 索引

当前 `kb_qa_pairs_v2` 已完成：

- mapping 修正
- 数据迁移
- alias 切换
- 读路径回归
- 写 alias 验证

后续 QA 由业务方继续做真实新文档入库验证。

旧索引 `kb_qa_pairs` 处理建议：

1. 观察 7 到 14 天。
2. 确认无新增写入。
3. snapshot。
4. close 或删除。

## 12. Docker 部署注意事项

### 12.1 脚本执行位置

优先在 AI 服务容器内执行，因为：

- Python 依赖一致。
- 网络能直接访问 ES。
- 与生产环境变量一致。

### 12.2 文件拷贝

```bash
docker cp offline_es_migration_package <ai_service_container>:/tmp/offline_es_migration_package
```

进入容器：

```bash
docker exec -it <ai_service_container> bash
cd /tmp/offline_es_migration_package/ai_service
```

### 12.3 日志保存

每次执行都保存：

- 命令
- 开始时间
- 结束时间
- 输出 JSON
- ES count 校验
- 错误文档样例

建议目录：

```text
/tmp/es_migration_logs/YYYYMMDD_HHMM/
```

## 13. 回滚策略

### 13.1 backfill 回滚

补字段类 update 不容易逐字段回滚。

因此执行前必须：

- dry-run。
- 小批量。
- 保留旧字段值。
- 输出更新前后样例。

如果补全逻辑错误：

- 使用 snapshot 恢复。
- 或用修正脚本按 `permission_version`/执行批次重新覆盖。

### 13.2 alias 迁移回滚

alias 切换类迁移必须准备反向 `_aliases` 命令。

示例：

```json
{
  "actions": [
    {"remove": {"index": "new_index", "alias": "read_alias"}},
    {"remove": {"index": "new_index", "alias": "write_alias"}},
    {"add": {"index": "old_index", "alias": "read_alias"}},
    {"add": {"index": "old_index", "alias": "write_alias", "is_write_index": true}}
  ]
}
```

## 14. 最终验收清单

- [ ] 导出 mapping/settings/template/alias。
- [ ] 完成权限字段缺失审计。
- [ ] 主文档索引 backfill dry-run 通过。
- [ ] 主文档索引小批量执行通过。
- [ ] 主文档索引全量执行通过。
- [ ] 辅助索引 backfill dry-run 通过。
- [ ] 辅助索引全量执行通过。
- [ ] `kb_doc_search_v1` 权限字段缺失数为 0。
- [ ] `kb_doc_meta_v2` 权限字段缺失数为 0。
- [ ] `kb_document_*` 权限字段缺失数为 0。
- [ ] 授权 token 查询可见。
- [ ] 无权限 token 查询不可见。
- [ ] 管理员 token 查询全量可见。
- [ ] 保留所有执行日志和输出 JSON。
