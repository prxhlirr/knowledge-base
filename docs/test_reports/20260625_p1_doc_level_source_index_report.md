# P1 文档级索引 source_index/index_code 补齐测试报告

测试时间：2026-06-25

## 1. 本批次任务

| 编号 | 任务 | 状态 |
| --- | --- | --- |
| P1-1 | `kb_doc_meta` 新入库写入 `source_index/index_code` | 完成 |
| P1-2 | `kb_doc_search` 新入库写入 `source_index/index_code` | 完成 |
| P1-3 | ES setup mapping 增加文档级权限字段 | 完成 |
| P1-4 | 历史回填脚本补 `source_index/index_code` | 完成 |
| P1-5 | strict mapping 未更新时写入兼容降级 | 完成 |

## 2. 变更范围

### 2.1 在线入库链路

修改：

- `ai_service/core/rag_pipeline.py`
- `ai_service/core/indexing/doc_indexer.py`

核心变化：

1. `rag_pipeline.py` 将 Python 最终解析出的 `target_index` 传入 `DocIndexer`。
2. `doc_indexer.update_doc_meta()` 新增 `source_index` 参数。
3. `doc_indexer.update_doc_search()` 新增 `source_index` 参数。
4. `kb_doc_meta` / `kb_doc_search` 写入：
   - `source_index`
   - `index_code`
   - `owner_unit_code`
   - `visible_unit_codes`
   - `permission_version`
5. `index_code` 当前按物理索引名推导，例如 `kb_document_public -> public`。
6. `owner_unit_code` 当前取 `owner_dept_id` 兼容字段。

### 2.2 ES mapping

修改：

- `ai_service/core/indexing/es_setup.py`

新增字段：

```text
source_index: keyword
index_code: keyword
owner_unit_code: keyword
visible_unit_codes: keyword
permission_version: long
```

覆盖对象：

1. `doc_meta_index_mapping()`
2. `doc_search_index_mapping()`
3. `_update_mapping()` 对 `DOC_META_INDEX` / `DOC_SEARCH_INDEX` 热追加字段

### 2.3 离线回填

修改：

- `ai_service/scripts/backfill_doc_meta_v2.py`
- `ai_service/scripts/backfill_kb_doc_search.py`

核心变化：

1. 从 ES hit 的 `_index` 读取实际物理索引。
2. 写入 `source_index`。
3. 从 `source_index` 推导 `index_code`。
4. 写入 `owner_unit_code/visible_unit_codes/permission_version`。

## 3. 兼容保护

当前 live `kb_doc_search_v1` 是 `dynamic: strict`，如果 mapping 未先热更新，直接写新增字段会失败。

本批次在 `DocIndexer` 中增加兼容降级：

1. 首次写入携带新字段。
2. 如果 ES 返回 `strict_dynamic_mapping_exception`。
3. 自动移除新增可选字段后重试。
4. 保障现有入库不因 mapping 未更新而失败。

说明：

该保护只用于“不阻断现有功能”。要让新字段真正落入 ES，仍需执行 `ES_SETUP_MODE=migrate` 或创建新 v2/v3 索引。

## 4. 测试命令与结果

### 4.1 Python 语法检查

命令：

```powershell
python -m py_compile `
  ai_service/core/indexing/doc_indexer.py `
  ai_service/core/rag_pipeline.py `
  ai_service/core/indexing/es_setup.py `
  ai_service/scripts/backfill_doc_meta_v2.py `
  ai_service/scripts/backfill_kb_doc_search.py
```

结果：通过。

### 4.2 Java 编译测试

命令：

```powershell
$env:MAVEN_OPTS='-Xmx768m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=128m'
mvn -q -DskipTests compile
```

结果：通过。

### 4.3 Java 目标单元测试

命令：

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=96m'
mvn -q '-Dtest=IndexAliasResolverTest,IndexAclSubjectServiceTest' '-DforkCount=0' test
```

结果：通过。

## 5. 功能验证推导

### 5.1 新入库

新入库路径：

```text
rag_pipeline.py target_index
  -> doc_indexer.update_doc_meta(..., source_index=target_index)
  -> doc_indexer.update_doc_search(..., source_index=target_index)
```

预期结果：

1. 如果目标 ES mapping 已包含新增字段，`kb_doc_meta` / `kb_doc_search` 记录会带上 `source_index/index_code`。
2. 如果目标 ES mapping 尚未更新，`kb_doc_search` strict mapping 触发异常后会剥离新增字段重试，旧功能不受影响。

### 5.2 历史回填

回填路径：

```text
旧 kb_document_* hit._index
  -> source_index
  -> index_code
  -> kb_doc_meta / kb_doc_search
```

预期结果：

1. 从多个物理索引重建 doc_search/meta 时，每个文档级记录能记录来源物理索引。
2. 后续 `SimilarityService` 可在 `kb_doc_meta_v3.source_index` 可用后恢复普通用户 meta KNN，并按角色可读索引过滤。
3. `KeywordRecallStrategy` / `HybridRecallStrategy` 后续可对 `kb_doc_search_v2.source_index` 加过滤，避免文档级预筛越权。

## 6. 未执行项

本批次没有直接对 live ES 执行 mapping 热更新或历史回填。

原因：

1. 用户已明确数据库 P0 手动执行；ES 迁移同样应在确认窗口执行。
2. 当前任务先完成代码与脚本落地，避免直接改动线上/本地 ES 数据。

上线前需要执行：

```bash
ES_SETUP_MODE=migrate python scripts/es_init.py
```

或由运维使用等效方式对 `kb_doc_meta_v2` / `kb_doc_search_v1` 追加 mapping。

## 7. 风险与控制

| 风险 | 等级 | 控制 |
| --- | --- | --- |
| mapping 未更新导致新字段无法写入 | 中 | 已增加 strict mapping fallback，不阻断入库 |
| `index_code` 当前由索引名推导，未接 DB 路由表 | 中 | 后续可从 `sys_index_routing` 同步标准编码 |
| `visible_unit_codes` 当前只用 `owner_dept_id` 兜底 | 中 | 后续接组织树生成完整上级链 |
| live ES 未回填前，相似推荐普通用户仍走 chunk fallback | 中 | 回填 `kb_doc_meta_v3.source_index` 后恢复 meta KNN |

## 8. 验收结论

本批次达到“代码和脚本可进入联调”的标准：

1. Python 语法检查通过。
2. Java 编译通过。
3. Java 目标单测通过。
4. 在线入库已具备写入 `source_index/index_code` 能力。
5. 历史回填脚本已具备从 `_index` 反填 `source_index/index_code` 能力。
6. strict mapping 未更新时不会阻断现有 doc_search 写入。

下一批建议：

1. 对 live ES 执行 mapping 热更新或创建 v2/v3 索引。
2. dry-run / 小批量运行 `backfill_doc_meta_v2.py` 与 `backfill_kb_doc_search.py`。
3. 改造 Java `kb_doc_search` 查询，在文档级预筛加入 `source_index` 过滤。

