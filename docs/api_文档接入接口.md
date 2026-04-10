# 知识库系统 文档接入 API 文档

版本：v1.0 | Base URL：`http://<host>:8080`

---

## 认证说明

所有接入接口均需在请求头携带内部服务凭证：

```
X-Internal-Token: <token>
```

Token 值由运维通过环境变量 `KB_INTERNAL_TOKEN` 配置，开发默认值为 `kb-dev-token-change-me-in-prod`。

---

## 接入流程概览

```
外部系统
  ├─ REST API → POST /api/v1/internal/doc/register
  └─ Kafka   → Topic: kb.doc.notify
                        ↓
              DocIngestService（统一入库）
                        ↓
              Redis 队列（DOC_TASK_QUEUE）
                        ↓
              Python Worker（rag_pipeline）
                        ↓
              Elasticsearch 向量化索引
```

---

## 一、REST API 接入

### 1.1 单文档注册

**接口：** `POST /api/v1/internal/doc/register`

**请求头：**

| Header | 必填 | 说明 |
|--------|------|------|
| `X-Internal-Token` | 是 | 内部服务凭证 |
| `Content-Type` | 是 | `application/json` |

**请求体字段：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `ingestType` | String | 是 | `SFTP` / `LOCAL` / `URL` |
| `credentialId` | String | SFTP 必填 | SFTP 凭据 ID（见第四节） |
| `filePath` | String | 与 dirPath 二选一 | 文件路径或 URL |
| `dirPath` | String | 与 filePath 二选一 | 目录路径（递归拉取） |
| `fileName` | String | 否 | 显示文件名（缺省取路径末段） |
| `visibility` | String | 否 | `PUBLIC` / `INTERNAL`(默认) / `DEPT` / `PRIVATE` |
| `deptCode` | String | DEPT时必填 | 部门编码 |
| `tag` | String | 否 | 标签（逗号分隔） |
| `unit` | String | 否 | 来源单位 |
| `docNumber` | String | 否 | 文号 |
| `owner` | String | 否 | 责任人 |
| `searchQueries` | String | 否 | 推荐搜索词（逗号分隔） |
| `publishTime` | String | 否 | 发布时间（yyyy-MM-dd） |
| `sourceSystem` | String | 否 | 来源系统标识（OA / DMS 等） |

---

**示例1：SFTP 单文件**

```bash
curl -X POST http://localhost:8080/api/v1/internal/doc/register \
  -H "X-Internal-Token: kb-dev-token-change-me-in-prod" \
  -H "Content-Type: application/json" \
  -d '{
    "ingestType": "SFTP",
    "credentialId": "fileserver-prod",
    "filePath": "/data/docs/2024/report.pdf",
    "visibility": "INTERNAL",
    "owner": "张三",
    "unit": "政法委办公室",
    "sourceSystem": "OA"
  }'
```

**示例2：SFTP 整目录**

```bash
curl -X POST http://localhost:8080/api/v1/internal/doc/register \
  -H "X-Internal-Token: kb-dev-token-change-me-in-prod" \
  -H "Content-Type: application/json" \
  -d '{
    "ingestType": "SFTP",
    "credentialId": "fileserver-prod",
    "dirPath": "/data/archive/2024/Q1",
    "visibility": "DEPT",
    "deptCode": "IT-001",
    "tag": "档案,2024,Q1",
    "sourceSystem": "ARCHIVE"
  }'
```

**示例3：HTTP URL 下载**

```bash
curl -X POST http://localhost:8080/api/v1/internal/doc/register \
  -H "X-Internal-Token: kb-dev-token-change-me-in-prod" \
  -H "Content-Type: application/json" \
  -d '{
    "ingestType": "URL",
    "filePath": "https://docs.example.com/policy/2024-policy.pdf",
    "fileName": "2024年政策文件.pdf",
    "visibility": "PUBLIC",
    "sourceSystem": "DMS"
  }'
```

**成功响应：**

```json
{
  "code": 200,
  "msg": "文档入库任务已提交",
  "batchId": "550e8400-e29b-41d4-a716-446655440000"
}
```

> `batchId` 可用于查询任务进度（见第三节）。

---

### 1.2 批量文档注册

**接口：** `POST /api/v1/internal/doc/register/batch`

单次最多 **200 条**，适合外部系统按天批量同步历史文件。

请求体为 `DocIngestRequest` 数组：

```bash
curl -X POST http://localhost:8080/api/v1/internal/doc/register/batch \
  -H "X-Internal-Token: kb-dev-token-change-me-in-prod" \
  -H "Content-Type: application/json" \
  -d '[
    {
      "ingestType": "SFTP",
      "credentialId": "fileserver-prod",
      "filePath": "/data/2024/doc1.pdf",
      "visibility": "INTERNAL",
      "sourceSystem": "OA"
    },
    {
      "ingestType": "SFTP",
      "credentialId": "fileserver-prod",
      "filePath": "/data/2024/doc2.docx",
      "visibility": "DEPT",
      "deptCode": "HR-002",
      "sourceSystem": "OA"
    }
  ]'
```

**成功响应：**

```json
{
  "code": 200,
  "msg": "批量提交完成",
  "batchIds": ["550e8400...", "660f9500..."],
  "failedCount": 0,
  "failedItems": []
}
```

---

## 二、Kafka 接入

### 2.1 消息格式

**Topic：** `kb.doc.notify`（可通过环境变量 `KAFKA_TOPIC_DOC_NOTIFY` 修改）

Producer 配置要求：
- `key.serializer` = `StringSerializer`
- `value.serializer` = `StringSerializer`
- 消息 Value 为 JSON 字符串，字段与 1.1 请求体完全一致

**消息示例：**

```json
{
  "ingestType": "SFTP",
  "credentialId": "fileserver-prod",
  "filePath": "/data/docs/2024/notice.pdf",
  "visibility": "INTERNAL",
  "owner": "李四",
  "unit": "信息化处",
  "docNumber": "信字[2024]12号",
  "publishTime": "2024-03-15",
  "sourceSystem": "OA"
}
```

**Java Producer 示例：**

```java
@Autowired
private KafkaTemplate<String, String> kafkaTemplate;

public void notifyNewDoc(String filePath) throws Exception {
    Map<String, String> payload = new HashMap<>();
    payload.put("ingestType",   "SFTP");
    payload.put("credentialId", "fileserver-prod");
    payload.put("filePath",     filePath);
    payload.put("visibility",   "INTERNAL");
    payload.put("sourceSystem", "OA");
    kafkaTemplate.send("kb.doc.notify", "OA",
        new ObjectMapper().writeValueAsString(payload));
}
```

**Python Producer 示例：**

```python
from kafka import KafkaProducer
import json

producer = KafkaProducer(
    bootstrap_servers=["localhost:9092"],
    value_serializer=lambda v: json.dumps(v, ensure_ascii=False).encode("utf-8"),
    key_serializer=lambda k: k.encode("utf-8")
)

producer.send(
    topic="kb.doc.notify",
    key="OA",
    value={
        "ingestType":   "SFTP",
        "credentialId": "fileserver-prod",
        "filePath":     "/data/docs/2024/notice.pdf",
        "visibility":   "INTERNAL",
        "sourceSystem": "OA"
    }
)
producer.flush()
```

### 2.2 Kafka ACK 与重试机制

| 场景 | 处理方式 |
|------|---------|
| JSON 格式错误 | 立即 ACK 跳过（记录警告日志） |
| 参数校验失败 | 立即 ACK 跳过 |
| SFTP/网络失败 | **不 ACK**，重启后重消费（配合幂等去重） |
| 入库成功 | 立即 ACK，提交 offset |

---

## 三、任务进度查询

```bash
GET /api/v1/admin/doc/batches/{batchId}/tasks
X-Internal-Token: <token>
```

**任务状态说明：**

| status | 含义 |
|--------|------|
| `PENDING` | 等待 Python Worker 处理 |
| `INDEXED` | 已写入 ES，向量化完成 |
| `ERROR` | 处理失败（见 errorMsg 字段） |

**响应示例：**

```json
{
  "code": 200,
  "data": [
    {
      "taskId": "abc123",
      "originalName": "report.pdf",
      "status": "INDEXED",
      "errorMsg": null,
      "createdAt": "2024-03-21T10:00:00"
    }
  ]
}
```

---

## 四、SFTP 凭据配置

管理员在 `application.yml` 中预先配置，外部系统只传 `credentialId`，不传密码：

```yaml
sftp:
  known-hosts-path: ~/.ssh/known_hosts
  credentials:
    fileserver-prod:           # credentialId = "fileserver-prod"
      host: ${SFTP_FS_HOST}
      port: 22
      user: ${SFTP_FS_USER}
      password: ${SFTP_FS_PASSWORD}
    fileserver-dev:            # credentialId = "fileserver-dev"
      host: localhost
      port: 22
      user: dev
      password: ${SFTP_DEV_PASSWORD}
```

首次连接新的 SFTP 服务器前，需将其公钥加入 known_hosts：

```bash
ssh-keyscan <sftp-host> >> ~/.ssh/known_hosts
```

---

## 五、错误码说明

| code | 含义 | 常见原因 |
|------|------|---------|
| 200 | 成功 | 任务已提交 |
| 400 | 参数错误 | credentialId 不存在 / DEPT 未传 deptCode |
| 401 | 未授权 | Token 缺失或无效 |
| 403 | 安全拦截 | URL 指向内网地址（SSRF 防护） |
| 500 | 服务错误 | SFTP 连接失败 / 文件不存在 |

---

## 六、visibility 可见性说明

| 值 | 含义 | 可见范围 |
|----|------|---------|
| `PUBLIC` | 公开 | 所有人（含匿名） |
| `INTERNAL` | 内部（默认） | 所有登录用户 |
| `DEPT` | 部门 | 指定部门及其上级部门 |
| `PRIVATE` | 私有 | 仅上传方 |
| `GRANT` | 授权 | 通过 GRANT 接口明确授权的用户 |
