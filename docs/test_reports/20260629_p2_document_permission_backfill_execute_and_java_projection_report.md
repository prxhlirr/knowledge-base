# P2 普通文档权限投影回填与 Java 响应透传测试报告

## 1. 测试目标

完成 `kb_document_*` 历史普通文档权限投影字段真实回填，并验证 Java `/api/v1/search` 最终响应能透传这些字段。

目标字段：

- `source_index`
- `index_code`
- `owner_unit_code`
- `visible_unit_codes`
- `permission_version`

## 2. 涉及文件

- `ai_service/scripts/backfill_document_permission_projection.py`
- `ai_service/tools_and_tests/test_backfill_document_permission_projection.py`
- `java_service/src/main/java/com/boyang/search/pipeline/steps/LiteralRecallStep.java`
- `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordResultAssembleStep.java`
- `java_service/src/test/java/com/boyang/search/pipeline/steps/DocumentPermissionProjectionAssembleTest.java`

## 3. ES 历史数据真实回填

执行命令：

```powershell
$env:ES_HOST='http://127.0.0.1:9200'
python ai_service/scripts/backfill_document_permission_projection.py --index kb_document_* --batch-size 200 --sample 5 --execute
```

执行结果：

| 指标 | 数值 |
| --- | ---: |
| scanned | 691 |
| owner_from_es | 691 |
| owner_from_pg | 0 |
| owner_defaulted | 0 |
| skipped_non_document_index | 0 |
| written | 691 |
| failed | 0 |

结论：

- 691 条历史普通文档 chunk 已写入权限投影字段。
- 所有 owner 均来自 ES 现有字段，没有使用默认兜底。

## 4. ES 回填后统计校验

| 索引 | total | source_index | index_code | owner_unit_code | visible_unit_codes | permission_version | missing |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `kb_document_public` | 22 | 22 | 22 | 22 | 22 | 22 | 0 |
| `kb_document_official` | 669 | 669 | 669 | 669 | 669 | 669 | 0 |
| `kb_document_law` | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| `kb_document_notice` | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| `kb_document_v1` | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| `kb_document_news` | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

二次 dry-run：

```powershell
$env:ES_HOST='http://127.0.0.1:9200'
python ai_service/scripts/backfill_document_permission_projection.py --index kb_document_* --batch-size 200 --sample 3
```

结果：

| 指标 | 数值 |
| --- | ---: |
| scanned | 0 |
| dry_run_updates | 0 |
| written | 0 |
| failed | 0 |

结论：历史普通文档权限投影字段已补齐，没有剩余待处理数据。

## 5. Java HTTP 初次复测暴露的问题

请求：

```text
POST /api/v1/search
X-Search-AppCode: ADMIN_MASTER_KEY
queryText=重点民生实事工程
searchMode=hybrid
```

初次结果：

```json
{
  "code": 200,
  "total": 1,
  "list_count": 1,
  "source_index": null,
  "index_code": null,
  "owner_unit_code": null,
  "visible_unit_codes": null,
  "permission_version": null
}
```

根因：

- ES 文档已经具备权限字段。
- 但普通文档命中走 `LiteralRecallStep` / `KeywordResultAssembleStep` 组装路径。
- 这两条路径只组装展示字段，没有把 ES `_source` 中的权限投影字段提升到最终响应顶层。
- 因此 `_source` 在后续出站时被丢弃后，HTTP 响应无法审计命中文档来源。

## 6. Java 修复内容

修复文件：

- `LiteralRecallStep.java`
- `KeywordResultAssembleStep.java`

修复逻辑：

- 在普通文档结果组装阶段新增权限字段提升：
  - `source_index`
  - `index_code`
  - `owner_unit_code`
  - `visible_unit_codes`
  - `permission_version`
- 优先读取 ES 顶层 `_source` 字段。
- 若顶层缺失，则 fallback 到 `metadata`。
- 空字符串不写入，避免伪造有效权限值。

## 7. 自动化测试

新增测试：

- `DocumentPermissionProjectionAssembleTest`

覆盖：

1. `LiteralRecallStep` 精确命中路径保留权限投影字段。
2. `KeywordResultAssembleStep` keyword 普通文档组装路径保留权限投影字段。

执行命令：

```powershell
mvn -q -Dtest=DocumentPermissionProjectionAssembleTest test
mvn -q "-Dtest=DocumentPermissionProjectionAssembleTest,SearchControllerQaPermissionProjectionTest,RerankStepPermissionProjectionTest,IndexAclGuardTest" test
mvn -q -DskipTests compile
```

结果：

- 新增单测通过
- 组合回归通过
- Java 编译通过

## 8. Java HTTP 修复后复测

请求：

```text
POST /api/v1/search
X-Search-AppCode: ADMIN_MASTER_KEY
queryText=重点民生实事工程
searchMode=hybrid
```

结果：

```json
{
  "code": 200,
  "total": 1,
  "list_count": 1,
  "file_name": "test_notice.docx",
  "source_index": "kb_document_official",
  "index_code": "official",
  "owner_unit_code": "global",
  "visible_unit_codes": ["global"],
  "permission_version": 1782722890119,
  "is_qa_answer": null
}
```

结论：

- 普通文档 HTTP 响应已能透传 ES 回填后的权限投影字段。
- 管理员真实请求可命中 `kb_document_official` 并返回可审计字段。
- ES 回填、Java 组装、HTTP 响应三段链路已完成闭环。

## 9. 本轮结论

1. `kb_document_*` 历史普通文档 691 条已完成真实权限投影回填。
2. ES 字段统计确认无缺失。
3. Java 普通文档响应字段丢失问题已修复。
4. 单测、组合回归、编译、真实 HTTP E2E 均通过。
5. 下一步建议继续验证角色级 `readable_source_indexes` 对普通文档检索结果的实际过滤效果。

