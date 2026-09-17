# 2026-06-29 P2 QA HTTP 权限过滤补测报告

## 任务范围

本批任务补测 AI HTTP 接口层是否应用 QA `readable_source_indexes` 权限过滤。

涉及文件：

- `ai_service/scratch/qa_filter_uvicorn_runner.py`

该 runner 仅用于本次验证，不修改业务逻辑。

## 验证服务启动方式

新增验证启动器行为：

- `AI_START_WORKER=false`
- `AUTO_BACKFILL_ON_STARTUP=false`
- `AI_MODEL_PRELOAD=false`
- `AI_CAPABILITIES=qa`
- `ES_HOST=http://localhost:9200`
- `AI_PORT=8001`

目的：

- 禁用 worker，避免消费 Redis 入库任务；
- 禁用启动回填，避免服务启动时修改索引；
- 禁用模型预加载，只验证 BM25 QA 查询路径。

## 启动验证结果

执行后端口检查：

```text
localhost:8001 TcpTestSucceeded=True
```

健康接口验证：

```text
/api/ai/health -> 200
/docs -> 200
```

结论：

- AI HTTP 服务启动成功。
- FastAPI 应用可访问。

## BM25 接口调用结果

调用接口：

- `/api/ai/qa/search/bm25`

测试用例：

- `readable_source_indexes=kb_document_public`
- `readable_source_indexes=kb_document_official`
- `readable_source_indexes=kb_document_official,kb_document_public`
- `readable_source_indexes=kb_document_law`

接口响应：

```text
status=200
count=0
```

所有用例均返回 0 条。

## 阻断原因分析

接口调用后立即复核 ES：

```text
Test-NetConnection -ComputerName localhost -Port 9200
TcpTestSucceeded=False
```

独立 ES 查询报错：

```text
ConnectionRefusedError: [WinError 10061] 由于目标计算机积极拒绝，无法连接。
```

判断：

- HTTP 服务本身启动成功；
- 但 AI 服务依赖的 `localhost:9200` 在接口补测时不可达；
- `/api/ai/qa/search/bm25` 代码对异常采用降级返回空结果；
- 因此本批 `count=0` 不能作为权限过滤业务结果，只能说明接口在 ES 不可用时降级为空。

## 已完成验证

上一批 ES 层验证仍然有效：

```text
official=401
public=18
official_public=419
law=0
```

样本隔离：

```text
test_gongshi.docx + public -> 18
test_gongshi.docx + official -> 0
test.doc + public -> 0
test.doc + official -> 50
```

本批新增确认：

- AI HTTP 服务可以用最小 QA 模式启动；
- FastAPI 健康接口可达；
- BM25 接口在 ES 不可达时返回空结果。

## 未完成项

HTTP 接口层权限过滤业务验证未完成。

原因：

- 补测期间 ES `localhost:9200` 不可达。

复测前置条件：

```text
localhost:9200 TcpTestSucceeded=True
localhost:8001 TcpTestSucceeded=True
```

## 后续复测命令建议

1. 先确认 ES：

```powershell
Test-NetConnection -ComputerName localhost -Port 9200
```

2. 启动最小 QA HTTP 服务：

```powershell
python ai_service/scratch/qa_filter_uvicorn_runner.py
```

3. 调用 `/api/ai/qa/search/bm25`：

```json
{
  "query_text": "总书记什么时候强调的？",
  "readable_source_indexes": "kb_document_official",
  "top_k": 20,
  "acl_tokens": []
}
```

4. 验证返回结果中的：

- `source_index`
- `index_code`
- `source`

不得跨越 `readable_source_indexes`。

## 本批结论

本批完成了 HTTP 服务启动可行性验证，但未完成 HTTP 接口权限过滤业务验证。

根因不是代码逻辑失败，而是接口补测时 ES 连接中断。需要在 ES 稳定可达后重新执行 HTTP 接口补测。
