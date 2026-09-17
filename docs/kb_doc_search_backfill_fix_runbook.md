# kb_doc_search 在线迁移手册（不停机）

> 适用场景：Windows 宿主机 + Docker 部署的离线环境，将 kb_document* 中的文档同步到 kb_doc_search。
> **本方案支持在线执行，无需停止 AI 或 Java 服务。**
>
> **执行方式**：支持 **宿主机** 和 **Docker 容器内** 两种执行方式，见第三节。
>
> | 环境 | ES 地址 | 说明 |
> |------|---------|------|
> | 宿主机执行 | `http://192.168.74.1:9200` | PowerShell 命令 |
> | 容器内执行 | `http://elasticsearch:9200` 或同宿主机地址 | bash 命令 |
>
> 认证：`admin` / `8732391`

---

## 一、问题背景

| 问题 | 根因 | 影响 |
|------|------|------|
| kb_doc_search 文档数 < kb_document* | ① 历史文档从未回填 ② 在线管道 `update_doc_search()` 失败被静默吞掉 ③ 无增量对账机制 | 搜索漏召回，用户搜不到已入库文档 |
| backfill 脚本 `_source` 缺字段 | 缺少 `metadata.doc_id`/`content_hash`/`owner` | doc_id 为空、_id 可能与在线管道不一致 |
| `_id` 生成不一致 | 在线管道用 `md5(source)_v{version}`，backfill 用 `content_hash` 或 `source` | 同一文档产生重复记录 |
| 已有文档字段缺失 | reconcile 模式只补缺，不修复已有文档的空字段 | 搜索 prefilter 数据不完整 |

**修复内容**：
1. 修复 `_id` 生成公式，与在线管道完全一致：`md5(source)_v{max_version}`
2. reconcile 模式增强：补缺失 + 修复空字段
3. BulkWriter 改用 `index` 动作，写入效率提升 2-3x
4. 新增 ES 健康检查、进度追踪、validate 验证模式

---

## 二、前置确认

```powershell
$esHost = "http://192.168.74.1:9200"
$cred = New-Object System.Management.Automation.PSCredential("admin", (ConvertTo-SecureString "8732391" -AsPlainText -Force))
$headers = @{ "Content-Type" = "application/json" }

# 1. ES 连通性
Invoke-RestMethod -Uri "$esHost/_cat/health?v" -Credential $cred

# 2. 别名指向（预期: 两个别名均指向 kb_doc_search_v1）
Invoke-RestMethod -Uri "$esHost/_cat/aliases?v&alias=kb_doc_search*" -Credential $cred

# 3. 文档数对比（核心基线）
$srcCount = Invoke-RestMethod -Method Post -Uri "$esHost/kb_document*/_search" -Credential $cred -Headers $headers -Body '{"size":0,"aggs":{"n":{"cardinality":{"field":"metadata.source"}}}}'
$docCount = Invoke-RestMethod -Uri "$esHost/kb_doc_search/_count" -Credential $cred
Write-Host "kb_document* unique sources: $($srcCount.aggregations.n.value)"
Write-Host "kb_doc_search docs: $($docCount.count)"
# 如果两个数不一致，说明存在数据缺口
```

---

## 三、执行在线迁移（无需停止服务）

脚本零外部依赖（纯标准库），支持 **两种执行方式**：

### 方式 A：Docker 容器内执行（推荐 — 零网络延迟）

脚本已打包在 AI 服务镜像内（`/app/scripts/`），但需要先更新修改后的版本：

```powershell
# ① 将修改后的脚本复制到运行中的容器
docker cp ai_service/scripts/backfill_kb_doc_search.py ai-service:/app/scripts/backfill_kb_doc_search.py
docker cp ai_service/scripts/diag_backfill.py ai-service:/app/scripts/diag_backfill.py

# ② 确认容器内 ES 地址连通性（根据实际部署选择）
# 如果 AI 服务在同一 docker-compose 中: ES_HOST=http://elasticsearch:9200
# 如果 ES 使用宿主机网络: ES_HOST=http://192.168.74.1:9200
docker exec ai-service python -c "
import urllib.request, json
resp = urllib.request.urlopen('http://elasticsearch:9200/')
print(json.loads(resp.read())['version']['number'])
"
```

**Dry Run（查看缺口）：**
```powershell
docker exec -e ES_HOST=http://192.168.74.1:9200 `
  -e ES_USER=admin -e ES_PASS=8732391 `
  -e BACKFILL_MODE=reconcile -e DRY_RUN=true `
  ai-service python -u scripts/backfill_kb_doc_search.py
```

**正式执行：**
```powershell
docker exec -e ES_HOST=http://192.168.74.1:9200 `
  -e ES_USER=admin -e ES_PASS=8732391 `
  -e BACKFILL_MODE=reconcile -e DRY_RUN=false `
  -e BACKFILL_BULK_DELAY=0.05 `
  ai-service python -u scripts/backfill_kb_doc_search.py
```

**后台执行 + 查看日志（长时间迁移推荐）：**
```powershell
# 后台启动迁移
docker exec -d -e ES_HOST=http://192.168.74.1:9200 `
  -e ES_USER=admin -e ES_PASS=8732391 `
  -e BACKFILL_MODE=reconcile -e DRY_RUN=false `
  ai-service sh -c "python -u scripts/backfill_kb_doc_search.py > /tmp/backfill.log 2>&1"

# 实时查看进度
docker exec ai-service tail -f /tmp/backfill.log

# 查看是否还在运行
docker exec ai-service ps aux | findstr backfill
```

**验证：**
```powershell
docker exec -e ES_HOST=http://192.168.74.1:9200 `
  -e ES_USER=admin -e ES_PASS=8732391 `
  -e BACKFILL_MODE=validate `
  ai-service python -u scripts/backfill_kb_doc_search.py
```

### 方式 B：宿主机执行

在宿主机直接运行，需要宿主机能访问 ES：

```powershell
$env:ES_HOST = "http://192.168.74.1:9200"
$env:ES_USER = "admin"
$env:ES_PASS = "8732391"
$env:BACKFILL_MODE = "reconcile"
$env:DRY_RUN = "false"
$env:BACKFILL_BULK_DELAY = "0.05"
python -u ai_service/scripts/backfill_kb_doc_search.py
```

### 3.1 输出说明

```
[Progress] 35.2% | 3500/9950 docs | chunks=24500 | 120 docs/s | ETA: 0m53s
```

- 每 30 秒输出一行进度
- 包含完成百分比、吞吐量、预计剩余时间
- ES 堆内存超过 85% 时自动降速

### 3.2 性能调优

| 参数 | 默认值 | 调优建议 |
|------|--------|----------|
| `BACKFILL_BULK_DELAY` | 0.1s | ES 健康 → `0.02`；ES 压力大 → `0.2` |
| `DOC_SEARCH_BACKFILL_BATCH_SIZE` | 2000 | 吞吐低 → 增大到 `5000` |
| `RECONCILE_BATCH_SIZE` | 200 | 每批处理的 source 数量 |

**断点续传**：
- 脚本中断后重新执行相同命令，自动从 checkpoint 恢复
- 容器内 checkpoint 文件：`/app/scripts/backfill_checkpoint.json`
- 宿主机 checkpoint 文件：`ai_service/scripts/backfill_checkpoint.json`

### 3.3 Full 模式（可选，全量重建）

```powershell
# 容器内执行
docker exec -e ES_HOST=http://192.168.74.1:9200 `
  -e ES_USER=admin -e ES_PASS=8732391 `
  -e BACKFILL_MODE=full -e DRY_RUN=false `
  ai-service python -u scripts/backfill_kb_doc_search.py
```

> 全量模式耗时较长（50-100万文档约 30-90 分钟），建议仅在必要时使用。

---

## 四、数据验证

### 4.1 自动化验证（validate 模式）

```powershell
# 容器内执行（推荐）
docker exec -e ES_HOST=http://192.168.74.1:9200 `
  -e ES_USER=admin -e ES_PASS=8732391 `
  -e BACKFILL_MODE=validate `
  ai-service python -u scripts/backfill_kb_doc_search.py

# 或宿主机执行
$env:ES_HOST = "http://192.168.74.1:9200"
$env:ES_USER = "admin"
$env:ES_PASS = "8732391"
$env:BACKFILL_MODE = "validate"
python -u ai_service/scripts/backfill_kb_doc_search.py
```

自动检查 5 项：

| # | 检查项 | 说明 |
|---|--------|------|
| 1 | 文档数一致性 | kb_document* unique sources == kb_doc_search count |
| 2 | 字段完整性 | doc_id、content_hash、source 非空 |
| 3 | 重复 source | is_latest=true 的 source 是否唯一 |
| 4 | `_id` 格式 | 是否匹配 `md5hex_vN` 在线管道格式 |
| 5 | `_id` hash 校验 | `_id` 前缀是否与 source 的 md5 一致 |

预期输出：`✅ Validation PASSED: 9950 documents, all 5 checks green`

### 4.2 手动验证（可选）

```powershell
# 1. 文档数一致
$srcCount = Invoke-RestMethod -Method Post -Uri "$esHost/kb_document*/_search" -Credential $cred -Headers $headers -Body '{"size":0,"aggs":{"n":{"cardinality":{"field":"metadata.source"}}}}'
$docCount = Invoke-RestMethod -Uri "$esHost/kb_doc_search/_count" -Credential $cred
Write-Host "source=$($srcCount.aggregations.n.value) | doc_search=$($docCount.count)"

# 2. 字段完整性抽检
curl.exe -s -u admin:8732391 "$esHost/kb_doc_search/_search" -H "Content-Type: application/json" -d '{"size":50,"_source":["source","chunk_count","is_latest","doc_id","content_hash"],"query":{"term":{"is_latest":true}}}' | python -c "
import sys, json
d = json.load(sys.stdin)
print(f'total: {d[\"hits\"][\"total\"][\"value\"]}')
for h in d['hits']['hits']:
    s = h['_source']
    ok = '✅' if s.get('doc_id') and s.get('content_hash') else '❌'
    print(f'  {ok} {s[\"source\"]:50s} chunks={s[\"chunk_count\"]:>3d} doc_id={s.get(\"doc_id\",\"\")[:20]}')
"

# 3. 精确匹配验证
curl.exe -s -u admin:8732391 "$esHost/kb_doc_search/_search" -H "Content-Type: application/json" -d '{"query":{"term":{"source":"test_notice.docx"}},"_source":["source","chunk_count","is_latest","doc_id"]}' | python -c "
import sys, json; d = json.load(sys.stdin)
print(f'命中: {d[\"hits\"][\"total\"][\"value\"]}')
"
```

---

## 五、回滚方案

| 场景 | 操作 |
|------|------|
| 迁移数据有误 | 重新运行 `BACKFILL_MODE=reconcile` 覆盖修复 |
| 搜索异常 | 关闭 prefilter：Java 配置 `SEARCH_DOC_SEARCH_ENABLED=false` |
| kb_doc_search 完全损坏 | 清空后重跑 full 模式 |

```powershell
# 清空 kb_doc_search 全部文档（仅紧急情况使用）
Invoke-RestMethod -Method Post -Uri "$esHost/kb_doc_search_write/_delete_by_query?conflicts=proceed" -Credential $cred -Headers $headers -Body '{"query":{"match_all":{}}}'
```

---

## 六、日常运维

```powershell
# 定期对账 — 容器内执行（推荐）
docker exec -e ES_HOST=http://192.168.74.1:9200 `
  -e ES_USER=admin -e ES_PASS=8732391 `
  -e BACKFILL_MODE=reconcile `
  ai-service python -u scripts/backfill_kb_doc_search.py

# 或宿主机执行
$env:ES_HOST = "http://192.168.74.1:9200"
$env:ES_USER = "admin"
$env:ES_PASS = "8732391"
$env:BACKFILL_MODE = "reconcile"
python -u ai_service/scripts/backfill_kb_doc_search.py

# Dry run（不写入，只看报告）
docker exec -e ES_HOST=http://192.168.74.1:9200 `
  -e ES_USER=admin -e ES_PASS=8732391 `
  -e BACKFILL_MODE=reconcile -e DRY_RUN=true `
  ai-service python -u scripts/backfill_kb_doc_search.py
```

---

## 七、完整流程摘要

```
Phase 0: 前置确认（2min）
  ├── ES 连通性
  ├── 别名指向确认
  └── 文档数基线对比

Phase 1: 部署脚本（1min）
  └── docker cp 修改后的脚本到容器内（方式A）
      或直接在宿主机执行（方式B）

Phase 2: Dry Run（5min）
  └── reconcile dry-run → 查看 missing + stale 数量

Phase 3: 在线执行（30-90min）
  ├── reconcile 正式执行（补缺 + 修字段）
  ├── 观察进度输出（throughput / ETA）
  └── 如中断，重新运行自动从 checkpoint 恢复

Phase 4: 验证（5min）
  ├── BACKFILL_MODE=validate 自动化验证
  └── 可选手动抽检

Phase 5: 持续运维
  └── 定期运行 reconcile 对账
```

---

## 八、与旧版方案的关键差异

| 维度 | 旧版（停机） | 新版（在线） |
|------|-------------|-------------|
| 服务状态 | 需停止 AI + Java 服务 | 无需停止任何服务 |
| `_id` 生成 | `content_hash` 或 `source`（与在线管道不一致） | `md5(source)_v{version}`（与在线管道一致） |
| 字段修复 | reconcile 只补缺失 | reconcile 补缺失 + 修复空字段 |
| 写入动作 | `update+upsert`（慢） | `index`（快 2-3x） |
| 批量大小 | 200条/批 | 1000条/批 |
| 可观测性 | 无进度输出 | 进度/ETA/吞吐量实时输出 |
| ES 保护 | 无监控 | 堆内存检查 + 自动降速 |
| 验证 | 手动检查文档数 | 5 项自动化验证 |

---

## 附录 A：kb_doc_search_v2 索引处置

### 当前状态

| 索引 | 文档数 | 别名 | 状态 |
|------|--------|------|------|
| `kb_doc_search_v1` | — | `kb_doc_search`(读) + `kb_doc_search_write`(写) | **活跃使用** |
| `kb_doc_search_v2` | — | 无 | **废弃** |

### 清理命令

```powershell
curl.exe -s -u admin:8732391 -X DELETE "http://192.168.74.1:9200/kb_doc_search_v2"
```

> 清理前确认无别名指向 v2。

---

## 附录 B：kb_doc_search 对 /search/home 搜索结果的影响分析

### 作用机制

kb_doc_search 在 `/search/home` 中作为 **prefilter（预过滤器）** 使用：

```
/search/home 请求
  │
  ├─ Step 1: kb_doc_search 预过滤
  │    └─ 查询 kb_doc_search 找到匹配 query 的 source 列表
  │    └─ 返回 candidateSources = ["doc_a.docx", "doc_b.docx", ...]
  │
  ├─ Step 2: 主 BM25 检索（kb_document*）
  │    └─ 在原有 DSL 基础上追加 filter: metadata.source IN candidateSources
  │
  └─ Step 3: 向量 KNN + RRF 融合 + Rerank
```

### 降级安全

| 场景 | 行为 |
|------|------|
| kb_doc_search 查询失败 | prefilter 返回空列表，不追加 filter，行为与无 prefilter 一致 |
| 开关控制 | `SEARCH_DOC_SEARCH_ENABLED=false` 可随时关闭 |
| 缺失文档 | prefilter 排除缺失文档 → **这就是本次迁移解决的核心问题** |

### 修复后的保证

reconcile 对账后 kb_doc_search 覆盖所有 kb_document* 文档，prefilter 候选集完整，
搜索结果范围与不使用 prefilter 时一致，不会漏召回。
