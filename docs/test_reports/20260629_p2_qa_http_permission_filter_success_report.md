# 2026-06-29 P2 QA HTTP 权限过滤补测成功报告

## 任务范围

本批任务重新补测 AI HTTP 接口层 QA 权限过滤。

修改文件：

- `ai_service/main.py`
- `ai_service/scratch/qa_filter_uvicorn_runner.py`

验证对象：

- `/api/ai/qa/search/bm25`
- `readable_source_indexes`
- `source_index/source_index.keyword`

## 本批修复

### 1. 验证 runner 导入路径修复

`qa_filter_uvicorn_runner.py` 显式设置：

```python
SERVICE_DIR = Path(__file__).resolve().parents[1]
os.chdir(SERVICE_DIR)
sys.path.insert(0, str(SERVICE_DIR))
```

原因：

- 直接从 `scratch` 启动时，`uvicorn main:app` 可能无法稳定加载 `ai_service/main.py`。
- 修复后可确保验证服务加载当前工作区代码。

### 2. QA 响应透传权限字段

`main.py` 的 KNN QA 与 BM25 QA `_source` 白名单新增：

- `source_index`
- `index_code`
- `owner_unit_code`
- `visible_unit_codes`

并同步放入响应 `metadata`。

原因：

- 权限过滤虽然在 ES 查询阶段生效，但原响应没有透传 `source_index/index_code`。
- 调用方和测试侧无法审计 QA 候选是否越过用户可读索引。

## 验证方式

由于端口启动受历史 8001 旧服务影响，本批最终使用 FastAPI `TestClient` 直接加载当前 `main.py` 路由执行。

为避免无关依赖干扰：

- mock `task_worker.main`
- 禁用 worker
- 禁用启动回填
- 禁用模型预加载
- 只启用 QA 能力

该方式仍走真实 FastAPI 路由函数和真实 ES 查询。

## Schema 验证

```text
schema_has_readable_source_indexes=True
```

结论：

- 当前加载的代码版本已包含 `readable_source_indexes`。
- 不是旧服务或旧 schema。

## HTTP BM25 权限过滤验证

请求统一使用：

```json
{
  "acl_tokens": ["_SUPER_ADMIN"]
}
```

原因：

- 本批目标是验证索引级权限过滤。
- `_SUPER_ADMIN` 只用于绕过文档级 ACL，避免 ACL 与索引权限混在一起影响判断。

### official 查询

请求：

```text
query=总书记什么时候强调的？
readable_source_indexes=kb_document_official
```

结果：

```text
count=12
indexes=['kb_document_official', ...]
```

结论：

- 返回结果全部来自 `kb_document_official`。

请求：

```text
query=总书记什么时候强调的？
readable_source_indexes=kb_document_law
```

结果：

```text
count=0
```

结论：

- 无权限索引不返回结果。

### public 查询

请求：

```text
query=公示期是多久？
readable_source_indexes=kb_document_public
```

结果：

```text
count=6
indexes=['kb_document_public', ...]
```

结论：

- 返回结果全部来自 `kb_document_public`。

请求：

```text
query=公示期是多久？
readable_source_indexes=kb_document_official
```

结果：

```text
count=6
indexes=['kb_document_official', ...]
```

结论：

- 该查询词在 official 索引中也有语义/文本命中，但返回结果仍全部来自 `kb_document_official`。
- 未出现 public 数据串到 official 权限下。

请求：

```text
query=公示期是多久？
readable_source_indexes=kb_document_official,kb_document_public
```

结果：

```text
count=12
indexes=['kb_document_public', 'kb_document_official', ...]
```

结论：

- 多索引权限可返回两个索引范围内的 QA。

## 自动化验证

执行命令：

```bash
python -m py_compile ai_service/main.py ai_service/scratch/qa_filter_uvicorn_runner.py
python ai_service/tools_and_tests/test_qa_source_index_filter.py
```

结果：

```text
PASS test_filter_uses_source_index_and_keyword_variant
PASS test_filter_ignores_alias_or_wildcard_to_avoid_guessing
PASS test_filter_ignores_non_document_indexes
```

## 非阻断告警

测试过程中出现：

```text
OperationLog 日志写库失败
psycopg2.OperationalError: no password supplied
```

判断：

- 这是测试环境 PG 操作日志写库失败。
- 不影响 BM25 QA 查询结果。
- 后续若要做干净接口测试，可为 TestClient 禁用操作日志中间件或配置测试 PG。

## 清理结果

已停止 8002 验证进程。

验证：

```text
localhost:8002 TcpTestSucceeded=False
```

## 本批结论

QA HTTP BM25 接口层权限过滤补测通过。

已确认：

- 当前 schema 包含 `readable_source_indexes`；
- BM25 路由会应用索引权限过滤；
- 返回结果的 `source_index` 均在请求允许范围内；
- 无权限索引返回 0；
- 响应已透传 `source_index/index_code`，可供 Java 侧审计。
