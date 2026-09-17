# 2026-06-29 P2 QA HTTP 权限过滤与字段透传修复报告

## 任务范围

本批任务继续补测 QA HTTP 接口层权限过滤，并修复响应结果无法审计权限来源的问题。

修改文件：

- `ai_service/main.py`
- `ai_service/scratch/qa_filter_uvicorn_runner.py`
- `ai_service/tools_and_tests/test_qa_source_index_filter.py`

## 发现的问题

### 1. 8001 旧服务 schema 不是当前代码

通过 `/openapi.json` 检查发现，8001 上运行的 `Bm25QaSearchRequest` 不包含：

```text
readable_source_indexes
```

因此早期 HTTP 请求传入该字段时会被旧服务忽略，不能作为当前代码验证结果。

### 2. QA 响应缺少权限字段

当前代码的 ES 查询已经应用 `source_index/source_index.keyword` 权限过滤，但 `_source` 白名单没有包含：

- `source_index`
- `index_code`
- `owner_unit_code`
- `visible_unit_codes`

导致 HTTP 响应里无法审计候选 QA 来自哪个文档物理索引。

## 修复内容

### 1. runner 导入路径固定

`qa_filter_uvicorn_runner.py` 显式切换到 `ai_service` 目录，并把该目录插入 `sys.path`。

目的：

- 确保验证时加载当前工作区 `ai_service/main.py`。

### 2. QA 响应透传权限投影字段

KNN QA 与 BM25 QA 的 `_source` 白名单新增：

```text
source_index
index_code
owner_unit_code
visible_unit_codes
```

响应 `metadata` 也同步写入这些字段。

目的：

- Java 侧、测试侧、审计日志均可判断 QA 候选是否越过 `readable_source_indexes`。

### 3. 轻量测试补充

`test_qa_source_index_filter.py` 新增：

```text
test_qa_response_source_projection_includes_permission_fields
```

用于防止后续误删权限字段透传。

## HTTP/TestClient 验证

由于 8001 存在旧服务干扰，最终使用 FastAPI `TestClient` 直接加载当前 `main.py` 路由。

为避免无关依赖阻断：

- mock `task_worker.main`
- 禁用 worker
- 禁用启动回填
- 禁用模型预加载
- 只启用 QA 能力

该方式仍执行真实 FastAPI 路由和真实 ES 查询。

Schema 验证：

```text
schema_has_readable_source_indexes=True
```

## 权限过滤结果

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

### 无权限索引查询

请求：

```text
query=总书记什么时候强调的？
readable_source_indexes=kb_document_law
```

结果：

```text
count=0
```

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

### official 查询 public 相关问题

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

- 该问题在 official 索引中也有文本命中；
- 返回结果仍全部来自请求允许的 `kb_document_official`；
- 没有 public 数据串入 official 权限。

### official + public 查询

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

- 多索引权限只返回允许集合内的数据。

## 自动化验证

执行命令：

```bash
python -m py_compile ai_service/main.py ai_service/scratch/qa_filter_uvicorn_runner.py ai_service/tools_and_tests/test_qa_source_index_filter.py
python ai_service/tools_and_tests/test_qa_source_index_filter.py
```

结果：

```text
PASS test_filter_uses_source_index_and_keyword_variant
PASS test_filter_ignores_alias_or_wildcard_to_avoid_guessing
PASS test_filter_ignores_non_document_indexes
PASS test_qa_response_source_projection_includes_permission_fields
```

## 清理验证

```text
localhost:8002 TcpTestSucceeded=False
```

结论：

- 验证端口无残留监听。

## 非阻断告警

TestClient 调用时出现操作日志写库失败：

```text
psycopg2.OperationalError: no password supplied
```

判断：

- 这是本地测试环境 PG 操作日志未配置导致；
- 不影响 QA BM25 查询结果；
- 后续可独立增加测试环境禁用操作日志或配置测试 PG。

## 本批结论

QA HTTP BM25 接口层权限过滤在当前代码路径下验证通过。

同时已修复：

- 响应缺少 `source_index/index_code`，导致无法审计候选权限来源的问题。

当前链路具备：

- Java/Python 请求参数：`readable_source_indexes`
- Python 查询过滤：`source_index` + `source_index.keyword`
- 历史 QA 数据：全部补齐 `source_index`
- HTTP 响应：透传权限投影字段
- 自动化测试：覆盖过滤 DSL 和响应字段投影
