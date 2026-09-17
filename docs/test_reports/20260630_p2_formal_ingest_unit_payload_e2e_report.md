# P2 正式入库入口单位权限 Payload 验证报告

## 验证目标

验证非 `global` 单位文档通过正式 Java 入库入口提交后，Redis 入库 payload 是否真实携带单位权限投影字段：

- `ownerUnitCode`
- `visibleUnitCodes`
- `permissionVersion`
- `acl_tokens_json`
- `targetIndex`

本轮只验证正式入口到 Redis 队列的前半链路。完整 ES 落库仍需要可运行的 Python Worker；当前本机 Python 缺少 `markitdown`，Worker 无法直接启动。

## 测试入口

接口：

```http
POST http://127.0.0.1:18080/api/v1/admin/doc/batch_import
X-Internal-Token: kb-dev-token-change-me-in-prod
```

请求体：

```json
{
  "sourceDir": "E:\\project\\AI\\knowledge-base\\scratch\\unit_acl_probe_dir",
  "targetIndex": "kb_document_law",
  "visibility": "DEPT",
  "deptCode": "620102",
  "tag": "law",
  "unit": "城关区",
  "owner": "unit-acl-e2e",
  "searchQueries": "unit-acl-probe-20260630",
  "publishTime": "2026-06-30"
}
```

测试文件：

```text
scratch/unit_acl_probe_dir/unit_acl_ingest_probe_20260630.txt
```

## 环境处理

Java 服务使用同一个 PowerShell 脚本临时启动、提交请求、读取 Redis、关闭服务。原因是当前工具调用结束后会回收临时启动的 Java 进程，不能跨工具调用保持 18080 常驻。

启动覆盖参数：

```bash
--server.port=18080
--spring.redis.host=127.0.0.1
--spring.redis.port=6379
--elasticsearch.host=127.0.0.1
--elasticsearch.port=9200
--ai.service.host=http://127.0.0.1:8001
--knowledge.base.local-import-mode=direct
--knowledge-base.extract.delivery-mode=sync
--kb.security.god-mode=false
--jwt.dev-mode=true
--search.trust-gateway-headers=true
```

## 验证结果

接口返回：

```json
{
  "javaReady": true,
  "importCode": 200,
  "batchId": "af18008c-261a-4092-8415-946132ef50dc",
  "beforeHigh": "0",
  "queueTaskId": "b30280ce-44fc-4a58-97d7-f694bac58652",
  "originalName": "unit_acl_ingest_probe_20260630.txt",
  "targetIndex": "kb_document_law",
  "visibility": "DEPT",
  "deptCode": "620102",
  "ownerUnitCode": "620102",
  "visibleUnitCodes": "620102,6201,62",
  "permissionVersion": 1782779694553,
  "aclTokensJson": "[\"dept::620102\",\"dept::6201\",\"dept::62\"]",
  "storageMode": "LOCAL_FS",
  "filePath": "E:\\project\\AI\\knowledge-base\\scratch\\unit_acl_probe_dir\\unit_acl_ingest_probe_20260630.txt"
}
```

结论：

- 正式 Java 入库入口可以成功提交任务。
- `targetIndex=kb_document_law` 正确进入 payload。
- `visibility=DEPT` 与 `deptCode=620102` 正确进入 payload。
- `ownerUnitCode=620102` 正确进入 payload。
- `visibleUnitCodes=620102,6201,62` 正确进入 payload，符合开发部门树 `620102 -> 6201 -> 62`。
- `acl_tokens_json=["dept::620102","dept::6201","dept::62"]` 与单位链一致。
- `storageMode=LOCAL_FS` 证明本次使用 direct 本地直读模式，未依赖 MinIO。

## 清理记录

Redis 队列清理：

```text
DOC_TASK_QUEUE_HIGH removed=1
DOC_TASK_QUEUE_HIGH len=0
DOC_TASK_QUEUE len=0
```

PostgreSQL 探针数据清理：

```text
kb_doc_outbox deleted=1
sys_doc_import_task deleted=1
sys_doc_batch deleted=1
```

清理后确认：

```text
sys_doc_batch 0
sys_doc_import_task 0
kb_doc_outbox 0
DOC_TASK_QUEUE_HIGH 0
DOC_TASK_QUEUE 0
```

## 未完成项

完整 ES 落库与 HTTP 正反向检索验证尚未执行。阻塞点是当前本机 Python 环境缺少 `markitdown`，`task_worker.py` 无法启动完整 RAGPipeline。

下一步需要二选一：

1. 补齐 AI Worker 运行依赖后，重新提交同一测试文档，让 Worker 消费 Redis 并写入 ES。
2. 使用已有容器/服务器上的 Worker 消费队列，再验证 `kb_document_law`、`kb_doc_meta`、`kb_doc_search` 与 QA 派生数据的权限字段。
