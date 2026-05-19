
# 知识库系统 开发测试文档

版本：v1.0 | 更新时间：2026-03-21

---

## 1. 测试环境准备

### 1.1 依赖服务

| 服务 | 默认地址 | 说明 |
|------|---------|------|
| Java 后端 | `http://localhost:8080` | Spring Boot 主服务 |
| Python AI | `http://localhost:8001` | 向量化/重排序 |
| Elasticsearch | `http://localhost:9200` | 向量+全文索引 |
| PostgreSQL | `localhost:5432/knowledge_base` | 元数据存储 |
| Redis | `localhost:6379` | 任务队列 + 缓存 |

### 1.2 环境变量

```bash
KB_INTERNAL_TOKEN=kb-dev-token-change-me-in-prod
STORAGE_LOCAL_PATH=/tmp/kb-uploads
REDIS_HOST=localhost
REDIS_PORT=6379
```

### 1.3 初始化检查

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:9200/_cluster/health?pretty
curl http://localhost:8001/api/ai/health
redis-cli ping
```

---

## 2. 接口清单

### 文档接入

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/api/v1/admin/doc/upload` | ⚠️ 待修复 A-2 | 文件上传 |
| POST | `/api/v1/admin/doc/batch_import` | ⚠️ 待修复 A-2 | 本地目录批量导入 |
| POST | `/api/v1/admin/doc/sftp_import` | ⚠️ 待修复 A-2 | SFTP 远程拉取 |
| POST | `/api/v1/admin/doc/url_import` | ⚠️ 待修复 A-3 | URL 下载 |
| POST | `/api/v1/internal/task/callback` | Token | Python Worker 回调 |
| GET | `/api/v1/admin/doc/batches` | Token | 批次列表 |
| GET | `/api/v1/admin/doc/batches/{batchId}/tasks` | Token | 批次子任务 |

### 文档管理

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/api/v1/admin/docs` | Token | 分页文档列表 |
| DELETE | `/api/v1/admin/docs/{id}` | Token | 删除文档 |
| PUT | `/api/v1/admin/docs/{id}/meta` | Token | 更新元数据 |
| GET | `/api/v1/admin/docs/{id}/versions` | Token | 版本列表 |
| POST | `/api/v1/admin/docs/{id}/grants` | Token | 授予访问权 |
| DELETE | `/api/v1/admin/docs/{id}/grants/{userId}` | Token | 撤销授权 |
| GET | `/api/v1/admin/docs/{id}/grants` | Token | 授权列表 |

### 搜索

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/api/v1/search` | AppCode 签名 | 混合检索 |
| POST | `/api/v1/search/chunk` | AppCode 签名 | 分片级检索 |

---

## 3. 文档接入测试

### TC-01 文件上传（正常流程）

```bash
curl -X POST http://localhost:8080/api/v1/admin/doc/upload \
  -H "X-Internal-Token: kb-dev-token-change-me-in-prod" \
  -F "files=@/path/to/test.pdf" \
  -F "visibility=INTERNAL" \
  -F "owner=张三" -F "unit=技术部"
```

**预期：** `{"code":200,"data":"<batchId>"}`

**验证：**

1. DB：`SELECT status FROM sys_doc_batch WHERE batch_id='<batchId>'` → `IMPORTING`
2. Redis：`LLEN DOC_TASK_QUEUE_HIGH`（文件<2MB 时）有值
3. 30-120s 后任务变为 `INDEXED`
4. ES：`GET /kb_document_v1/_search?q=metadata.source.keyword:test.pdf` 有结果

---

### TC-02 文件类型校验（Magic Number）

将非 PDF 文件改名为 `.pdf` 上传，预期：`error_count+1`，不入队列，日志含"类型校验不通过"

---

### TC-03 文件大小限制

上传 >100MB 文件，预期：跳过，`error_count+1`

---

### TC-04 幂等上传（内容哈希去重）

同一文件上传两次，第二次预期：跳过，日志含"内容哈希重复"

> ⚠️ 已知缺陷 B-1：hash 仅取前 8KB，大文件前 8KB 相同时可能误判

---

### TC-05 批量目录导入

```bash
curl -X POST http://localhost:8080/api/v1/admin/doc/batch_import \
  -H "Content-Type: application/json" \
  -d '{"sourceDir":"/tmp/test-docs","visibility":"INTERNAL"}'
```

| 场景 | 输入 | 预期 |
|------|------|------|
| 空目录 | 无文档的目录 | 400 |
| 不存在路径 | `/nonexistent` | 400 |
| 空参数 | `sourceDir:""` | 400 |
| 路径遍历 ⚠️ | `/etc` | 当前可遍历（A-2 待修复） |

---

### TC-06 任务回调鉴权

```bash
# 正常回调
curl -X POST http://localhost:8080/api/v1/internal/task/callback \
  -H "Content-Type: application/json" \
  -H "X-Internal-Token: kb-dev-token-change-me-in-prod" \
  -d '{"taskId":"<taskId>","status":"INDEXED","errorMsg":""}'

# 错误 Token → 预期 401
curl -X POST http://localhost:8080/api/v1/internal/task/callback \
  -H "Content-Type: application/json" \
  -H "X-Internal-Token: wrong-token" \
  -d '{"taskId":"x","status":"INDEXED","errorMsg":""}'
```

---

### TC-07 批次查询

```bash
# 所有批次
curl http://localhost:8080/api/v1/admin/doc/batches \
  -H "X-Internal-Token: kb-dev-token-change-me-in-prod"

# 按状态过滤子任务
curl "http://localhost:8080/api/v1/admin/doc/batches/<batchId>/tasks?status=ERROR" \
  -H "X-Internal-Token: kb-dev-token-change-me-in-prod"
```

---

## 4. 权限管理测试

### TC-10 GRANT 授权

```bash
curl -X POST http://localhost:8080/api/v1/admin/docs/<registryId>/grants \
  -H "Content-Type: application/json" \
  -H "X-Internal-Token: kb-dev-token-change-me-in-prod" \
  -d '{"granteeId":"user001","granteeName":"张三","grantedBy":"admin","expiresAt":"2026-12-31T23:59:59"}'
```

**验证 DB：**

```sql
SELECT * FROM kb_doc_grants WHERE grantee_id='user001' AND is_active=1;
```

---

### TC-11 GRANT 撤销

```bash
curl -X DELETE http://localhost:8080/api/v1/admin/docs/<registryId>/grants/user001 \
  -H "X-Internal-Token: kb-dev-token-change-me-in-prod"
```

**验证：** DB 中 `is_active=0`（软删除，保留审计历史）

---

### TC-12 GRANT 幂等

重复授权同一用户，预期：返回"已存在有效授权"，DB 不重复插入

---

### TC-13 授权列表查询

```bash
curl http://localhost:8080/api/v1/admin/docs/<registryId>/grants \
  -H "X-Internal-Token: kb-dev-token-change-me-in-prod"
```

---

### TC-14 DEPT 权限隔离

前置：上传 `visibility=DEPT,deptCode=IT-001` 的文档

```bash
# 同部门 → 应命中
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{"appCode":"test-app","query":"文档关键词","user_id":"u1","user_dept_code":"IT-001"}'

# 跨部门 → 应无结果
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{"appCode":"test-app","query":"文档关键词","user_id":"u2","user_dept_code":"HR-002"}'
```

---

### TC-15 PRIVATE 权限隔离

```bash
# 上传者可见
curl -X POST http://localhost:8080/api/v1/search \
  -d '{"appCode":"test-app","query":"私有关键词","user_id":"uploader_user"}'

# 他人不可见
curl -X POST http://localhost:8080/api/v1/search \
  -d '{"appCode":"test-app","query":"私有关键词","user_id":"other_user"}'
```

---

### TC-16 匿名用户仅见 PUBLIC

不传 `user_id`，验证所有结果 `metadata.visibility` 均为 `PUBLIC`（或字段缺失）

---

### TC-17 可见度变更 ES 一致性

```bash
# 变更为 PRIVATE
curl -X PUT http://localhost:8080/api/v1/admin/docs/<id>/meta \
  -H "Content-Type: application/json" \
  -H "X-Internal-Token: kb-dev-token-change-me-in-prod" \
  -d '{"visibility":"PRIVATE"}'

# 1-5s 后搜索验证权限已生效
# 若 ES 故障，检查补偿队列
redis-cli llen es:sync:pending
```

---

## 5. 混合检索测试

### TC-20 基础检索

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{
    "appCode": "test-app",
    "query": "知识库检索测试",
    "topK": 5,
    "user_id": "user001",
    "user_dept_code": "IT-001"
  }'
```

**验收：**
- `code=200`，`results` 非空
- 响应时间 < 5000ms（SLA 保护）
- 所有结果 `visibility` 符合当前用户权限
- 结果按 `_rrf_score` 降序排列

---

### TC-21 精确查询（Fast-Track）

输入文档标题或文号（如《某管理办法》），验证：
- 日志出现 `[Fast-Track] Exact Title/ID match detected`
- 响应时间 < 500ms

> ⚠️ 已知缺陷 A-5：Pre-Flight 当前无权限过滤，无权文档可能泄露

---

### TC-22 短词 Lexical 快速路径

4字以内查询（如"保密"），验证日志出现 `[Lexical Fast-Path] Bypassing Embedding`

---

### TC-23 LLM 改写 + HyDE

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{"appCode":"test-app","query":"怎么防止审讯过程中有假话","user_id":"user001"}'
```

**验证日志序列：**
1. `[HyDE Gate] cos_sim=...`
2. `[ColBERT xxx] N docs scored`
3. `[LLM Reranker] N docs scored via qwen2.5:7b`

---

### TC-24 搜索缓存

```bash
# 第一次 Cache Miss
curl -X POST http://localhost:8080/api/v1/search \
  -d '{"appCode":"test-app","query":"缓存测试","user_id":"u1"}'

# 第二次应命中缓存，响应 < 100ms
curl -X POST http://localhost:8080/api/v1/search \
  -d '{"appCode":"test-app","query":"缓存测试","user_id":"u1"}'

redis-cli keys "search:cache:*" | wc -l
```

---

### TC-25 SLA 超时降级

模拟 AI 服务慢响应，Java 层应在 `sla-ms`（5000ms）内返回 BM25 降级结果

---

### TC-26 AppCode 校验

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -d '{"appCode":"invalid-code","query":"测试"}'
```

**预期：** 4xx 错误

---

## 6. 公共异常场景

| 场景 | 输入 | 预期 |
|------|------|------|
| 无 Token 访问管理接口 | 不带 X-Internal-Token | 401 |
| 错误 Token | X-Internal-Token: wrong | 401 |
| 空 query | query: "" | 400 |
| DEPT 无 deptCode | visibility=DEPT, deptCode="" | 400 |
| 空文件 | 0 字节 | 跳过或 400 |
| 路径穿越文件名 | `../../etc/passwd.pdf` | 净化为合法文件名 |

---

## 7. 自动化测试脚本

### 冒烟测试（Bash）

```bash
#!/bin/bash
BASE="http://localhost:8080"
PASS=0; FAIL=0

check() {
  [ "$2" == "$3" ] && { echo "[PASS] $1"; ((PASS++)); } \
                   || { echo "[FAIL] $1 (got=$2 exp=$3)"; ((FAIL++)); }
}

# 无效 Token 应返回 401
CODE=$(curl -s -X POST "$BASE/api/v1/internal/task/callback" \
  -H "Content-Type:application/json" -H "X-Internal-Token:wrong" \
  -d '{"taskId":"x","status":"INDEXED","errorMsg":""}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['code'])")
check "TC-06 无效Token" "$CODE" "401"

# 正常检索应返回 200
CODE=$(curl -s -X POST "$BASE/api/v1/search" \
  -H "Content-Type:application/json" \
  -d '{"appCode":"test-app","query":"测试","user_id":"u1"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('code',0))")
check "TC-20 基础检索" "$CODE" "200"

echo "结果: 通过 $PASS | 失败 $FAIL"
```

### 权限隔离验证（Python）

```python
import requests

BASE = "http://localhost:8080"

def search(query, user_id=None, dept_code=None):
    payload = {"appCode": "test-app", "query": query}
    if user_id:   payload["user_id"] = user_id
    if dept_code: payload["user_dept_code"] = dept_code
    return requests.post(f"{BASE}/api/v1/search", json=payload)\
                   .json().get("data", {}).get("results", [])

# 匿名用户不应见 INTERNAL
results = search("内部文档关键词")
for r in results:
    vis = r["_source"]["metadata"].get("visibility")
    assert vis in ["PUBLIC", None], f"匿名用户不应看到 {vis} 文档"
print("[PASS] 匿名权限隔离")

# DEPT 隔离
r_in  = search("部门关键词", user_id="u1", dept_code="IT-001")
r_out = search("部门关键词", user_id="u2", dept_code="HR-002")
assert len(r_in) > 0,   "[FAIL] 同部门应有结果"
assert len(r_out) == 0, "[FAIL] 跨部门应无结果"
print("[PASS] DEPT 权限隔离")
```

### 批次追踪工具（Python）

```python
import requests, time

def wait_batch(batch_id, token, timeout=120):
    start = time.time()
    while True:
        tasks = requests.get(
            f"http://localhost:8080/api/v1/admin/doc/batches/{batch_id}/tasks",
            headers={"X-Internal-Token": token}
        ).json().get("data", [])
        pending = sum(1 for t in tasks if t["status"] == "PENDING")
        done    = sum(1 for t in tasks if t["status"] in ("INDEXED","ERROR"))
        print(f"  总={len(tasks)} 完成={done} 等待={pending}")
        if pending == 0:
            return tasks
        if time.time() - start > timeout:
            raise TimeoutError(f"批次 {batch_id} 超时")
        time.sleep(5)
```

---

## 8. 验收标准

### 功能验收

| 功能点 | 标准 |
|--------|------|
| 文件上传 | 上传成功后 120s 内 ES 可搜索 |
| 类型校验 | 非法类型 100% 拦截 |
| 权限过滤 | PRIVATE/DEPT/GRANT 对无权用户 0 结果 |
| GRANT 授权 | 授权后下次搜索即可命中 |
| Token 鉴权 | 无效 Token 100% 返回 401 |
| 幂等上传 | 同内容重复上传不产生重复 ES chunk |

### 性能基准（开发环境）

| 指标 | 目标 |
|------|------|
| 普通检索 P50 | < 1500ms |
| Fast-Track 精确查询 P50 | < 500ms |
| 缓存命中 P50 | < 100ms |
| SLA 保护上限 | 5000ms |
| Redis 缓存命中率 | > 30% |

---

## 9. 已知缺陷与注意事项

> 以下场景修复前测试结果与预期不符，属已知缺陷，不计回归 Bug

| 缺陷 | 影响场景 |
|------|---------|
| A-1 GRANT 双轨 | GRANT 权限检索可能不一致 |
| A-2 三接口无鉴权 | batch/sftp/url 可无 Token 访问 |
| A-3 SSRF | url_import 可访问内网 |
| A-5 Pre-Flight 无权限 | 精确查询可绕过权限 |
| B-1 8KB 哈希 | 大文件去重可能误判 |

### 测试数据准备

- 各 visibility 文档各一份：PUBLIC / INTERNAL / DEPT(deptCode=IT-001) / PRIVATE / GRANT
- 测试用户：普通用户、IT 部门用户、被 GRANT 授权用户
- 确保 ES 索引 `kb_document_v1` 已创建（DbInitRunner 自动初始化）

### 清理测试数据

```sql
DELETE FROM sys_doc_batch WHERE source_dir LIKE '/tmp/test%';
```

```bash
curl -X POST "http://localhost:9200/kb_document_v1/_delete_by_query" \
  -H "Content-Type: application/json" \
  -d '{"query":{"term":{"metadata.tag.keyword":"test"}}}'
```
