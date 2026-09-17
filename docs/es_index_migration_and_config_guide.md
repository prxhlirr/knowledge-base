# 离线环境 ES 索引迁移 + 配置指南

> 适用于 Windows 宿主机 + Docker 部署的离线环境。
> 所有命令均为 **PowerShell** 语法（Windows 环境）。
> ES 地址：`http://192.168.74.1:9200`，认证：`admin` / `8732391`

---

## ⚠️ Windows PowerShell 注意事项

在 Windows PowerShell 中，`curl` 是 `Invoke-WebRequest` 的别名，不是真正的 curl。
本文档所有 ES 操作使用以下两种方式：

- **方式 A**：`curl.exe`（Windows 自带真正的 curl，注意要加 `.exe` 后缀）
- **方式 B**：`Invoke-RestMethod`（PowerShell 原生 cmdlet，推荐）

> 对于带 JSON body 的复杂操作（创建索引、reindex、切换别名），推荐使用 **方式 B**，
> 因为 PowerShell 对单引号和换行的处理会导致 curl.exe 的 JSON body 解析出错。

---

## 一、前置确认

### 1.1 确认当前环境

```powershell
# === 在宿主机 PowerShell 中执行 ===

# 定义 ES 连接信息（后续命令复用）
$esHost = "http://192.168.74.1:9200"
$esUser = "admin"
$esPass = "8732391"

# 确认 ES 可达
Invoke-RestMethod -Uri "$esHost/_cat/health?v" -Credential (New-Object System.Management.Automation.PSCredential($esUser, (ConvertTo-SecureString $esPass -AsPlainText -Force)))

# 确认当前 kb_doc_search 别名指向
Invoke-RestMethod -Uri "$esHost/_cat/aliases?v&alias=kb_doc_search*" -Credential (New-Object System.Management.Automation.PSCredential($esUser, (ConvertTo-SecureString $esPass -AsPlainText -Force)))
# 预期输出：
# alias               index             is_write_index
# kb_doc_search       kb_doc_search_v1  -
# kb_doc_search_write kb_doc_search_v1  true

# 确认当前文档数
$resp = Invoke-RestMethod -Uri "$esHost/kb_doc_search/_count" -Credential (New-Object System.Management.Automation.PSCredential($esUser, (ConvertTo-SecureString $esPass -AsPlainText -Force)))
Write-Host "当前 kb_doc_search 文档数: $($resp.count)"

# 确认 AI 容器状态
docker ps | Select-String "ai-service"
```

---

## 二、索引迁移（修复 ngram 中文支持）

### 2.1 迁移原理

```
迁移前:
  物理索引 kb_doc_search_v1 ← 别名 [kb_doc_search(读), kb_doc_search_write(写)]
  问题: doc_ngram_tokenizer 排除了中文字符，ngram 子串匹配完全失效

迁移后:
  物理索引 kb_doc_search_v2 ← 别名 [kb_doc_search(读), kb_doc_search_write(写)]
  修复: 不限制 token_chars，中文文件名/文号可正常 ngram 分词
```

**零停机原理**：ES 别名切换是原子操作（单次 HTTP 请求），写入自动路由到新索引。

### 2.2 Step 1 — 暂停 AI 文档写入

```powershell
# 停止 AI 容器
docker stop ai-service

# 确认已停止（应无输出）
docker ps | Select-String "ai-service"
```

> **为什么需要暂停？** Reindex 期间如果有新文档写入旧索引 `kb_doc_search_v1`，这些文档不会自动出现在 `kb_doc_search_v2`。暂停写入确保数据一致性。

### 2.3 Step 2 — 创建新索引

#### 方式 A：通过 AI 容器内脚本（推荐）

> **注意**：需要先重建镜像，因为 `max_ngram_diff=6` 的配置在代码中。
> 如果不想重建镜像，直接用方式 B。

```powershell
# 先重建 AI 镜像（包含修复后的 es_setup.py）
Set-Location ai_service
docker build -t knowledge-base-ai:1.0.0 .

# 临时启动容器执行初始化
docker run --rm --network host `
  -e ES_HOST=http://192.168.74.1:9200 `
  -e ES_USER=admin `
  -e ES_PASS=8732391 `
  -e ES_SETUP_MODE=init `
  -e KB_DOC_SEARCH_INDEX=kb_doc_search_v2 `
  knowledge-base-ai:1.0.0 `
  python scripts/es_init.py
```

#### 方式 B：手动通过 PowerShell 创建

```powershell
# 创建新索引
$body = @{
  settings = @{
    number_of_shards = 1
    number_of_replicas = 0
    # ES 默认限制 max_ngram_diff <= 1，但我们的 ngram 使用 min=2 max=8（diff=6）
    max_ngram_diff = 6
    analysis = @{
      tokenizer = @{
        doc_ngram_tokenizer = @{
          type = "ngram"
          min_gram = 2
          max_gram = 8
        }
      }
      analyzer = @{
        ik_smart = @{ type = "custom"; tokenizer = "ik_smart" }
        ik_max_word = @{ type = "custom"; tokenizer = "ik_max_word" }
        doc_ngram = @{
          type = "custom"
          tokenizer = "doc_ngram_tokenizer"
          filter = @("lowercase")
        }
      }
    }
  }
  mappings = @{
    dynamic = "strict"
    properties = @{
      doc_id = @{ type = "keyword" }
      doc_version = @{ type = "integer" }
      content_hash = @{ type = "keyword" }
      source = @{
        type = "keyword"
        fields = @{
          text = @{ type = "text"; analyzer = "ik_max_word"; search_analyzer = "ik_smart" }
          ngram = @{ type = "text"; analyzer = "doc_ngram"; search_analyzer = "doc_ngram" }
        }
      }
      source_name = @{ type = "keyword" }
      title = @{
        type = "text"
        analyzer = "ik_max_word"
        search_analyzer = "ik_smart"
        fields = @{
          keyword = @{ type = "keyword"; ignore_above = 256 }
          ngram = @{ type = "text"; analyzer = "doc_ngram"; search_analyzer = "doc_ngram" }
        }
      }
      document_number = @{
        type = "keyword"
        fields = @{
          text = @{ type = "text"; analyzer = "ik_max_word"; search_analyzer = "ik_smart" }
          ngram = @{ type = "text"; analyzer = "doc_ngram"; search_analyzer = "doc_ngram" }
        }
      }
      keywords = @{ type = "keyword" }
      tags = @{ type = "keyword" }
      entities = @{ type = "keyword" }
      section_titles = @{ type = "text"; analyzer = "ik_max_word"; search_analyzer = "ik_smart" }
      doc_terms = @{ type = "text"; analyzer = "ik_max_word"; search_analyzer = "ik_smart" }
      summary = @{ type = "text"; analyzer = "ik_max_word"; search_analyzer = "ik_smart" }
      representative_chunk_ids = @{ type = "keyword" }
      doc_type = @{ type = "keyword" }
      data_source = @{ type = "keyword" }
      is_latest = @{ type = "boolean" }
      acl_tokens = @{ type = "keyword" }
      visibility = @{ type = "keyword" }
      owner_dept_id = @{ type = "keyword" }
      publish_time = @{ type = "date"; format = "yyyy-MM-dd||epoch_millis" }
      chunk_count = @{ type = "integer" }
      updated_at = @{ type = "date"; format = "epoch_millis" }
    }
  }
} | ConvertTo-Json -Depth 10

$cred = New-Object System.Management.Automation.PSCredential("admin", (ConvertTo-SecureString "8732391" -AsPlainText -Force))
$headers = @{ "Content-Type" = "application/json" }

Invoke-RestMethod -Method Put -Uri "http://192.168.74.1:9200/kb_doc_search_v2" -Credential $cred -Headers $headers -Body $body
Write-Host "✅ kb_doc_search_v2 索引创建成功"
```

验证索引创建：

```powershell
Invoke-RestMethod -Uri "http://192.168.74.1:9200/_cat/indices?v&index=kb_doc_search*" -Credential $cred
# 预期：看到 kb_doc_search_v1 和 kb_doc_search_v2 两个索引
```

### 2.4 Step 3 — Reindex 数据

```powershell
# 同步 reindex（数据量 < 10万条，通常几秒完成）
$reindexBody = @{
  source = @{ index = "kb_doc_search_v1" }
  dest = @{ index = "kb_doc_search_v2" }
} | ConvertTo-Json

$result = Invoke-RestMethod -Method Post -Uri "http://192.168.74.1:9200/_reindex?wait_for_completion=true&timeout=10m" -Credential $cred -Headers $headers -Body $reindexBody
Write-Host "Reindex 完成: total=$($result.total), failures=$($result.failures.Count)"

# 如果数据量 > 10万条，使用异步 reindex：
# $asyncResult = Invoke-RestMethod -Method Post -Uri "http://192.168.74.1:9200/_reindex?wait_for_completion=false" -Credential $cred -Headers $headers -Body $reindexBody
# $taskId = $asyncResult.task
# Write-Host "异步任务ID: $taskId"
# 查看进度：
# Invoke-RestMethod -Uri "http://192.168.74.1:9200/_tasks/$taskId" -Credential $cred
```

### 2.5 Step 4 — 验证数据完整性

```powershell
# 比对文档数
$v1 = Invoke-RestMethod -Uri "http://192.168.74.1:9200/kb_doc_search_v1/_count" -Credential $cred
$v2 = Invoke-RestMethod -Uri "http://192.168.74.1:9200/kb_doc_search_v2/_count" -Credential $cred
Write-Host "v1: $($v1.count) 条 | v2: $($v2.count) 条"
# 预期：两者相等

# 验证 ngram 分词生效（核心验证！）
$analyzeBody = @{
  analyzer = "doc_ngram"
  text = "甘肃省人民政府关于加强碳排放的通知"
} | ConvertTo-Json

$tokens = Invoke-RestMethod -Method Post -Uri "http://192.168.74.1:9200/kb_doc_search_v2/_analyze" -Credential $cred -Headers $headers -Body $analyzeBody
Write-Host "ngram 分词结果:"
$tokens.tokens | ForEach-Object { Write-Host "  $($_.token)" }
# 预期：包含 "甘肃"、"肃省"、"省人"、"人民"、"民政" 等 2-8 字 ngram token
# 如果输出为空，说明 ngram 未生效（不应该发生）

# 验证 ngram 搜索能力
$searchBody = @{
  query = @{ match = @{ "source.ngram" = @{ query = "人民政府" } } }
  size = 3
} | ConvertTo-Json

$searchResult = Invoke-RestMethod -Method Post -Uri "http://192.168.74.1:9200/kb_doc_search_v2/_search" -Credential $cred -Headers $headers -Body $searchBody
Write-Host "ngram 搜索 '人民政府' 命中: $($searchResult.hits.total.value) 条"
```

### 2.6 Step 5 — 原子切换别名（切割）

**这是最关键的一步**，一个原子操作完成切换，不会出现中间状态：

```powershell
$aliasBody = @{
  actions = @(
    @{ remove = @{ index = "kb_doc_search_v1"; alias = "kb_doc_search" } }
    @{ remove = @{ index = "kb_doc_search_v1"; alias = "kb_doc_search_write" } }
    @{ add = @{ index = "kb_doc_search_v2"; alias = "kb_doc_search" } }
    @{ add = @{ index = "kb_doc_search_v2"; alias = "kb_doc_search_write"; is_write_index = $true } }
  )
} | ConvertTo-Json -Depth 5

Invoke-RestMethod -Method Post -Uri "http://192.168.74.1:9200/_aliases" -Credential $cred -Headers $headers -Body $aliasBody
Write-Host "✅ 别名切换完成"
```

### 2.7 Step 6 — 切换后验证

```powershell
# 确认别名指向新索引
Invoke-RestMethod -Uri "http://192.168.74.1:9200/_cat/aliases?v&alias=kb_doc_search*" -Credential $cred
# 预期：
# kb_doc_search       kb_doc_search_v2  -     -
# kb_doc_search_write kb_doc_search_v2  -     true

# 通过别名查询确认数据可访问
$afterSwitch = Invoke-RestMethod -Uri "http://192.168.74.1:9200/kb_doc_search/_count" -Credential $cred
Write-Host "切换后 kb_doc_search 文档数: $($afterSwitch.count)"
# 预期：与 Step 4 的 v2 count 相同
```

### 2.8 Step 7 — 恢复 AI 服务

```powershell
# 重启 AI 容器
docker start ai-service

# 确认容器正常运行
docker ps | Select-String "ai-service"

# 等待服务启动
Start-Sleep -Seconds 30

# 确认 AI 服务健康
Invoke-RestMethod -Uri "http://192.168.74.1:8001/api/ai/health"

# 查看启动日志
docker logs --tail 50 ai-service 2>&1 | Select-String "ESSetup|DocSearch|ERROR"
```

### 2.9 Step 8 — 更新 AI 容器环境变量

在 `ai_service/docker-compose.yml` 中添加环境变量，确保后续 `es_init.py` 指向新索引：

```yaml
# ai_service/docker-compose.yml 的 environment 中添加：
environment:
  - KB_DOC_SEARCH_INDEX=kb_doc_search_v2
```

然后重新创建容器使配置生效：

```powershell
Set-Location ai_service
docker-compose up -d knowledge-base-ai
```

### 2.10 Step 9 — 清理旧索引（24小时后确认无问题）

```powershell
Invoke-RestMethod -Method Delete -Uri "http://192.168.74.1:9200/kb_doc_search_v1" -Credential $cred
Write-Host "✅ 旧索引 kb_doc_search_v1 已删除"
```

---

## 三、回滚方案

如果迁移后发现问题，**1 秒内回滚**（前提：旧索引未被删除）：

```powershell
$rollbackBody = @{
  actions = @(
    @{ remove = @{ index = "kb_doc_search_v2"; alias = "kb_doc_search" } }
    @{ remove = @{ index = "kb_doc_search_v2"; alias = "kb_doc_search_write" } }
    @{ add = @{ index = "kb_doc_search_v1"; alias = "kb_doc_search" } }
    @{ add = @{ index = "kb_doc_search_v1"; alias = "kb_doc_search_write"; is_write_index = $true } }
  )
} | ConvertTo-Json -Depth 5

Invoke-RestMethod -Method Post -Uri "http://192.168.74.1:9200/_aliases" -Credential $cred -Headers $headers -Body $rollbackBody
Write-Host "✅ 已回滚到 kb_doc_search_v1"
```

---

## 四、Task 3/4 配置步骤

### 4.1 配置项说明

| 配置项 | 默认值 | 用途 |
|--------|--------|------|
| `search.doc-search.write-index` | `kb_doc_search_write` | deleteDoc 时清理 kb_doc_search 的写入别名 |
| `editor.similarity.meta-write-index` | `kb_doc_meta_write` | deleteDoc + ACL 传播时操作 doc_meta 的写入别名 |

### 4.2 各环境配置

**开发环境**：无需任何配置，`application.yml` 已包含默认值。

**Docker 部署**：如果使用默认别名（`kb_doc_search_write`），不需要额外配置。否则在 `docker-compose-offline.yml` 添加：

```yaml
knowledge-base-java:
  environment:
    - KB_DOC_SEARCH_WRITE_ALIAS=kb_doc_search_write
```

### 4.3 验证别名存在

```powershell
$cred = New-Object System.Management.Automation.PSCredential("admin", (ConvertTo-SecureString "8732391" -AsPlainText -Force))

Invoke-RestMethod -Uri "http://192.168.74.1:9200/_cat/aliases?v&alias=kb_doc_search_write" -Credential $cred
Invoke-RestMethod -Uri "http://192.168.74.1:9200/_cat/aliases?v&alias=kb_doc_meta_write" -Credential $cred

# 如果别名不存在，通过 AI 容器初始化：
docker run --rm --network host `
  -e ES_HOST=http://192.168.74.1:9200 `
  -e ES_USER=admin `
  -e ES_PASS=8732391 `
  -e ES_SETUP_MODE=init `
  knowledge-base-ai:1.0.0 `
  python scripts/es_init.py
```

### 4.4 重新构建 Java 服务镜像

```powershell
Set-Location java_service
docker build -t knowledge-base-java:1.0.0 .
docker-compose -f ..\docker-compose-offline.yml up -d knowledge-base-java
```

### 4.5 功能验证

#### 验证 deleteDoc（Task 3）

```powershell
$cred = New-Object System.Management.Automation.PSCredential("admin", (ConvertTo-SecureString "8732391" -AsPlainText -Force))
$headers = @{ "Content-Type" = "application/json" }

# 查看一个文档在 kb_doc_search 中的状态
$searchBody = @{ query = @{ term = @{ is_latest = $true } }; _source = @("source", "is_latest") } | ConvertTo-Json -Depth 5
$doc = Invoke-RestMethod -Method Post -Uri "http://192.168.74.1:9200/kb_doc_search/_search?size=1" -Credential $cred -Headers $headers -Body $searchBody
$sourceName = $doc.hits.hits[0]._source.source
Write-Host "测试文档: $sourceName, is_latest=$($doc.hits.hits[0]._source.is_latest)"

# 通过管理界面删除该文档，然后再次查询
$checkBody = @{ query = @{ term = @{ source = $sourceName } } } | ConvertTo-Json -Depth 3
$after = Invoke-RestMethod -Method Post -Uri "http://192.168.74.1:9200/kb_doc_search/_search" -Credential $cred -Headers $headers -Body $checkBody
Write-Host "删除后 is_latest=$($after.hits.hits[0]._source.is_latest)"
# 预期：false

# 检查 Java 日志
docker logs --tail 20 kb-java 2>&1 | Select-String "DocSearch"
```

#### 验证 ACL 传播（Task 4）

```powershell
# 授权操作后，检查三个索引的 acl_tokens
$sourceName = "测试文档名"  # 替换为实际文档名

# chunk 索引
$chunkQuery = @{ query = @{ term = @{ "metadata.source" = $sourceName } }; _source = @("acl_tokens") } | ConvertTo-Json -Depth 5
$chunkResult = Invoke-RestMethod -Method Post -Uri "http://192.168.74.1:9200/kb_document/_search?size=1" -Credential $cred -Headers $headers -Body $chunkQuery
Write-Host "chunk acl_tokens: $($chunkResult.hits.hits[0]._source.acl_tokens -join ', ')"

# doc_meta 索引
$metaQuery = @{ query = @{ term = @{ source = $sourceName } }; _source = @("acl_tokens") } | ConvertTo-Json -Depth 3
$metaResult = Invoke-RestMethod -Method Post -Uri "http://192.168.74.1:9200/kb_doc_meta_write/_search?size=1" -Credential $cred -Headers $headers -Body $metaQuery
Write-Host "meta acl_tokens: $($metaResult.hits.hits[0]._source.acl_tokens -join ', ')"

# doc_search 索引
$searchQuery = @{ query = @{ term = @{ source = $sourceName } }; _source = @("acl_tokens") } | ConvertTo-Json -Depth 3
$searchResult = Invoke-RestMethod -Method Post -Uri "http://192.168.74.1:9200/kb_doc_search_write/_search?size=1" -Credential $cred -Headers $headers -Body $searchQuery
Write-Host "search acl_tokens: $($searchResult.hits.hits[0]._source.acl_tokens -join ', ')"

# 预期：三个索引的 acl_tokens 都包含新授权的 token

# 检查 Java 日志
docker logs --tail 20 kb-java 2>&1 | Select-String "DocACLProjection"
```

---

## 五、完整部署流程（按顺序执行）

```
Phase 1: ES 索引迁移
  ├── docker stop ai-service                    （暂停写入）
  ├── 创建 kb_doc_search_v2 索引               （docker run 或 PowerShell）
  ├── Reindex v1 → v2                          （Invoke-RestMethod）
  ├── 验证数据完整性 + ngram 分词                （比对 count + _analyze）
  ├── 原子切换别名                              （POST _aliases）
  └── 验证切换成功                              （确认别名指向 v2）

Phase 2: 服务更新
  ├── 更新 ai_service/docker-compose.yml       （KB_DOC_SEARCH_INDEX=kb_doc_search_v2）
  ├── 重新构建 Java 镜像                        （docker build）
  ├── docker start ai-service                  （启动 AI）
  ├── docker-compose up -d knowledge-base-java （启动 Java）
  └── 验证健康检查                              （/api/ai/health）

Phase 3: 功能验证
  ├── 搜索"焚烧"确认关键词检索有结果            （Task 1 验证）
  ├── 删除文档确认 kb_doc_search 清理            （Task 3 验证）
  ├── 授权/撤销确认三索引同步                    （Task 4 验证）
  └── 中文文件名 ngram 搜索                     （Task 2 验证）

Phase 4: 清理（24小时后）
  └── 删除 kb_doc_search_v1 旧索引
```
