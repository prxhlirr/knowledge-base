# kb_document_* 离线补全与迁移实施步骤

## 1. 实施目标

本方案用于在 AI 服务 Docker 部署、生产环境可能离线的前提下，对 `kb_document_*` 主文档索引完成以下工作：

1. 确认现有 ES mapping 是否满足亿级检索目标。
2. 确认新入库数据是否已经完整覆盖目标 mapping 字段。
3. 补全历史数据缺失的权限与检索字段。
4. 在必要时执行离线索引迁移。

核心原则：

- 先确认目标 mapping，再补历史数据。
- 新入库链路不满足目标字段前，不允许迁移。
- 只缺字段时优先 backfill，不必迁移。
- 字段类型、analyzer、vector 参数、shard 数需要调整时，必须新建索引迁移。
- `kb_document_*` 必须逐索引迁移，不允许一次性全量切换。

## 2. 总体阶段

```text
阶段一：Mapping Readiness 审计
阶段二：新入库数据门禁验证
阶段三：历史数据缺失项补全
阶段四：补全后审计
阶段五：按需离线迁移 kb_document_* 索引
阶段六：切换后回归与观察
```

第三步“离线索引迁移”不是必然执行项，只有当阶段一确认存在无法原地修复的问题时才执行。

## 3. 阶段一：Mapping Readiness 审计

### 3.1 目标

确认当前 template 和 `kb_document_*` 物理索引是否满足目标 mapping。

### 3.2 重点检查项

| 字段/配置 | 目标要求 |
| --- | --- |
| `content` | `text`, `ik_max_word`, `search_analyzer=ik_smart` |
| `display_content` | `text`，可带 `keyword` 子字段 |
| `vector` | `dense_vector`, `dims=1024`, `similarity=cosine` |
| `sparse_vector` | `rank_features` |
| `acl_tokens` | `keyword` |
| `source_index` | `keyword` |
| `index_code` | `keyword` |
| `owner_unit_code` | `keyword` |
| `visible_unit_codes` | `keyword` |
| `permission_version` | `long` |
| `doc_version` | `integer` |
| `is_latest` | `boolean` |
| `publish_time` | `date` |
| `tags` | `keyword` |
| `number_of_shards` | 按生产容量配置，不硬编码 |
| `number_of_replicas` | 生产至少 1 |

### 3.3 建议工具

新增或使用：

```text
ai_service/scripts/audit_es_mapping_readiness.py
```

建议命令：

```bash
python -X utf8 scripts/audit_es_mapping_readiness.py \
  --es-host "$ES_HOST" \
  --indices "kb_document_*" \
  --output /tmp/es_mapping_readiness.json
```

### 3.4 阶段一通过标准

- template 已满足新建索引目标 mapping。
- 新索引创建后权限字段会是纯 `keyword`。
- `vector` 参数正确。
- 生产 shard/replica 可配置。
- 如果现有索引字段类型不符合，明确是否进入迁移。

## 4. 阶段二：新入库数据门禁验证

### 4.1 目标

确认新写入链路已经能完整写入目标字段，否则历史补全和迁移没有意义。

### 4.2 验证方式

通过真实入库或模拟 worker payload，写入一篇测试文档。

建议选择低风险测试索引：

```text
kb_document_law
```

### 4.3 必查索引

- `kb_document_law`
- `kb_doc_search_v1`
- `kb_doc_meta_v2`
- `kb_qa_pairs_v2`，如果 QA 由业务方单独验证，可跳过

### 4.4 必查字段

```text
acl_tokens
source_index
index_code
owner_unit_code
visible_unit_codes
permission_version
doc_version
is_latest
content
vector
```

### 4.5 通过标准

- 主文档索引字段完整。
- 辅助索引字段完整。
- 写 alias 实际落到目标物理索引。
- 授权 token 可以检索到。
- 无权限 token 检索不到。
- 管理员 token 可见。

## 5. 阶段三：历史数据缺失项补全

### 5.1 目标

补齐历史数据缺失的权限字段和检索字段。

### 5.2 历史缺失项审计

使用：

```text
ai_service/scripts/audit_permission_projection_readiness.py
```

命令：

```bash
python -X utf8 scripts/audit_permission_projection_readiness.py \
  --es-host "$ES_HOST" \
  --indices "kb_document_*,kb_doc_search_v1,kb_doc_meta_v2" \
  --output /tmp/permission_audit_before.json
```

### 5.3 主文档索引补全

使用：

```text
ai_service/scripts/backfill_document_permission_projection.py
```

dry-run：

```bash
python -X utf8 scripts/backfill_document_permission_projection.py \
  --es-host "$ES_HOST" \
  --indices "kb_document_*" \
  --batch-size 500 \
  --limit 1000 \
  --dry-run \
  --output /tmp/backfill_document_dry_run.json
```

小批量执行：

```bash
python -X utf8 scripts/backfill_document_permission_projection.py \
  --es-host "$ES_HOST" \
  --indices "kb_document_*" \
  --batch-size 200 \
  --limit 1000 \
  --execute \
  --output /tmp/backfill_document_batch1.json
```

全量执行：

```bash
python -X utf8 scripts/backfill_document_permission_projection.py \
  --es-host "$ES_HOST" \
  --indices "kb_document_*" \
  --batch-size 500 \
  --execute \
  --output /tmp/backfill_document_full.json
```

### 5.4 辅助索引补全

使用：

```text
ai_service/scripts/backfill_auxiliary_permission_projection.py
```

dry-run：

```bash
python -X utf8 scripts/backfill_auxiliary_permission_projection.py \
  --es-host "$ES_HOST" \
  --indices "kb_doc_search_v1,kb_doc_meta_v2" \
  --batch-size 500 \
  --limit 1000 \
  --dry-run \
  --output /tmp/backfill_auxiliary_dry_run.json
```

执行：

```bash
python -X utf8 scripts/backfill_auxiliary_permission_projection.py \
  --es-host "$ES_HOST" \
  --indices "kb_doc_search_v1,kb_doc_meta_v2" \
  --batch-size 500 \
  --execute \
  --output /tmp/backfill_auxiliary_full.json
```

## 6. 阶段四：补全后审计

执行：

```bash
python -X utf8 scripts/audit_permission_projection_readiness.py \
  --es-host "$ES_HOST" \
  --indices "kb_document_*,kb_doc_search_v1,kb_doc_meta_v2" \
  --output /tmp/permission_audit_after.json
```

通过标准：

- `acl_tokens` 缺失数为 0。
- `source_index` 缺失数为 0。
- `index_code` 缺失数为 0。
- `owner_unit_code` 缺失数为 0。
- `visible_unit_codes` 缺失数为 0。
- `permission_version` 缺失数为 0。
- 授权/无权限/管理员三类检索回归通过。

## 7. 阶段五：按需离线迁移

### 7.1 进入迁移的条件

只有满足以下任一条件才迁移：

- 字段类型不符合目标，例如 `acl_tokens` 不是纯 `keyword`。
- analyzer/search_analyzer 需要调整。
- `vector` 参数需要调整。
- shard 数需要调整。
- 需要清理历史动态 mapping 污染。

如果只是字段缺失，补全后可以暂缓迁移。

### 7.2 迁移顺序

建议顺序：

1. `kb_document_law`
2. `kb_document_notice`
3. `kb_document_v1`
4. `kb_document_news`
5. `kb_document_public`
6. `kb_document_official`

原则：

- 空索引先迁移。
- 小索引先迁移。
- 大索引最后迁移。
- 每个索引单独验收。

### 7.3 迁移工具

建议新增：

```text
ai_service/scripts/migrate_kb_document_index_v2.py
```

必须支持：

```text
--source-index
--target-index
--batch-size
--limit
--dry-run
--execute
--create-target
--switch-alias
--output
```

默认行为：

- 不写入。
- 不切 alias。
- 不删除旧索引。

### 7.4 单索引迁移流程

以 `kb_document_official` 为例。

#### 7.4.1 暂停写入

暂停文档入库 worker 或 Java 入库入口，避免迁移期间继续写旧索引。

#### 7.4.2 导出基线

```bash
curl -s "$ES_HOST/_cat/aliases/kb_document*?h=alias,index,is_write_index&format=json" \
  > /tmp/kb_document_alias_before.json

curl -s "$ES_HOST/kb_document_official/_mapping" \
  > /tmp/kb_document_official.mapping.before.json

curl -s "$ES_HOST/kb_document_official/_settings" \
  > /tmp/kb_document_official.settings.before.json

curl -s "$ES_HOST/kb_document_official/_count" \
  > /tmp/kb_document_official.count.before.json
```

#### 7.4.3 创建目标索引

```bash
python -X utf8 scripts/migrate_kb_document_index_v2.py \
  --source-index kb_document_official \
  --target-index kb_document_official_v2 \
  --create-target \
  --dry-run \
  --output /tmp/migrate_official_create_dry_run.json
```

实际创建：

```bash
python -X utf8 scripts/migrate_kb_document_index_v2.py \
  --source-index kb_document_official \
  --target-index kb_document_official_v2 \
  --create-target \
  --execute \
  --output /tmp/migrate_official_create.json
```

#### 7.4.4 dry-run

```bash
python -X utf8 scripts/migrate_kb_document_index_v2.py \
  --source-index kb_document_official \
  --target-index kb_document_official_v2 \
  --batch-size 500 \
  --limit 1000 \
  --dry-run \
  --output /tmp/migrate_official_dry_run.json
```

#### 7.4.5 小批量执行

```bash
python -X utf8 scripts/migrate_kb_document_index_v2.py \
  --source-index kb_document_official \
  --target-index kb_document_official_v2 \
  --batch-size 200 \
  --limit 1000 \
  --execute \
  --output /tmp/migrate_official_batch1.json
```

#### 7.4.6 全量执行

```bash
python -X utf8 scripts/migrate_kb_document_index_v2.py \
  --source-index kb_document_official \
  --target-index kb_document_official_v2 \
  --batch-size 500 \
  --execute \
  --output /tmp/migrate_official_full.json
```

#### 7.4.7 校验

校验项：

- 新旧 count 一致。
- 权限字段缺失数为 0。
- `vector` 存在。
- `content` 存在。
- `_id` 保持一致。
- 授权 token 可见。
- 无权限 token 不可见。

#### 7.4.8 alias 原子切换

```json
{
  "actions": [
    {"remove": {"index": "kb_document_official", "alias": "kb_document"}},
    {"remove": {"index": "kb_document_official", "alias": "kb_document_official_write"}},
    {"add": {"index": "kb_document_official_v2", "alias": "kb_document"}},
    {"add": {"index": "kb_document_official_v2", "alias": "kb_document_official_write", "is_write_index": true}}
  ]
}
```

执行：

```bash
curl -s -X POST "$ES_HOST/_aliases" \
  -H "Content-Type: application/json" \
  -d @/tmp/switch_kb_document_official_v2.json
```

#### 7.4.9 写入验证

通过 `kb_document_official_write` 写入临时测试文档。

验收：

- 实际 `_index = kb_document_official_v2`。
- 旧索引无新增。
- 删除临时测试文档后数量恢复。

#### 7.4.10 恢复写入

恢复 worker 或 Java 入库入口。

## 8. Docker 离线执行

### 8.1 离线包结构

```text
offline_es_migration_package/
  ai_service/
    scripts/
      audit_es_mapping_readiness.py
      audit_permission_projection_readiness.py
      backfill_document_permission_projection.py
      backfill_auxiliary_permission_projection.py
      migrate_kb_document_index_v2.py
    core/
      permissions/
      indexing/
    tools_and_tests/
  docs/
    runbooks/
    es_exports/
```

### 8.2 拷贝进容器

```bash
docker cp offline_es_migration_package <ai_service_container>:/tmp/offline_es_migration_package
docker exec -it <ai_service_container> bash
cd /tmp/offline_es_migration_package/ai_service
```

### 8.3 环境变量

```bash
export ES_HOST=http://elasticsearch:9200
export ES_USER=
export ES_PASS=
```

如果 ES 有认证：

```bash
export ES_USER=elastic
export ES_PASS='******'
```

### 8.4 ES 连通性检查

```bash
python - <<'PY'
import os, urllib.request
es = os.getenv("ES_HOST", "http://elasticsearch:9200")
with urllib.request.urlopen(es + "/_cluster/health", timeout=10) as resp:
    print(resp.read().decode())
PY
```

## 9. 回滚策略

### 9.1 backfill 回滚

字段补全类更新不容易逐字段撤销，因此必须：

- 先 dry-run。
- 小批量执行。
- 输出变更样例。
- 保留执行报告。
- 有 snapshot 时优先 snapshot。

如果补全逻辑错误：

- 优先从 snapshot 恢复。
- 或根据执行报告和 `permission_version` 重新覆盖修正。

### 9.2 alias 回滚

以 `kb_document_official_v2` 回滚为例：

```json
{
  "actions": [
    {"remove": {"index": "kb_document_official_v2", "alias": "kb_document"}},
    {"remove": {"index": "kb_document_official_v2", "alias": "kb_document_official_write"}},
    {"add": {"index": "kb_document_official", "alias": "kb_document"}},
    {"add": {"index": "kb_document_official", "alias": "kb_document_official_write", "is_write_index": true}}
  ]
}
```

回滚后必须重新验证：

- alias 状态。
- 授权检索。
- 无权限检索。
- 写 alias 落点。

## 10. 最终验收清单

- [ ] Mapping readiness 审计通过。
- [ ] 新入库数据字段完整。
- [ ] 历史缺失项审计已输出。
- [ ] 主文档 backfill dry-run 通过。
- [ ] 主文档 backfill 小批量执行通过。
- [ ] 主文档 backfill 全量执行通过。
- [ ] 辅助索引 backfill dry-run 通过。
- [ ] 辅助索引 backfill 全量执行通过。
- [ ] 补全后缺失项为 0。
- [ ] 判断是否需要迁移。
- [ ] 如需迁移，逐索引迁移。
- [ ] alias 原子切换完成。
- [ ] 授权/无权限/管理员检索回归通过。
- [ ] 写 alias 验证通过。
- [ ] 旧索引保留观察。

## 11. 当前建议

当前不建议直接进入 `kb_document_*` 全量迁移。

建议先完成：

1. `audit_es_mapping_readiness.py`
2. 新入库样例门禁验证
3. 历史权限字段补全
4. 补全后审计

如果补全后检索已满足生产要求，可以暂缓迁移。

如果后续确认必须统一字段类型、调整 shard/replica 或 analyzer，再按本方案逐索引迁移。
