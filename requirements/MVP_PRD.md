# 文档1：需求功能点（MVP版）

## 一、核心目标

聚焦 “数据准、切片准、检索准、运维稳、权限严”，解决公文检索准确率低、多格式无法统一搜索、老系统效果差的问题，支持政务工作人员/领导检索权限范围内的最新公文。

## 二、功能优先级划分

### P0 必须实现

| 功能模块     | 具体功能点                                                                     |
| -------- | ------------------------------------------------------------------------- |
| 文档接入     | 1. 历史文档批量导入（分批次，支持失败重试+错误报告）<br>2. 新文档自动触发增量向量化<br>3. 支持Word/文字版PDF解析     |
| 文档处理     | 1. 按语义完整性智能分片<br>2. BGE-m3模型向量化（FP16量化）<br>3. 元数据提取（规则优先，支持补录）            |
| 检索核心     | 1. BM25+向量kNN混合检索（ES 8.6原生支持）<br>2. RRF混合排序<br>3. 结构化筛选（时间/发文单位/文号/公文类型）  |
| 结果展示     | 1. 文档列表（标题+摘要+权限标签）<br>2. 命中片段高亮<br>3. 跳转现有系统原文预览（预留接口）                   |
| 权限控制     | 1. 对接自研Token认证接口<br>2. 检索结果过滤（自己创建/参与流程/下级公开文档）                           |
| 版本与数据一致性 | 1. 文档版本标记（is_latest/status）<br>2. 最新版优先检索，过期/废止文档过滤<br>3. 文档删除/修订联动ES数据更新 |
| 运维与日志    | 1. 搜索日志记录（查询词/点击结果/无结果）<br>2. 批量导入进度展示<br>3. 查询向量Redis缓存（保障1s响应）          |
| 降级策略     | AI服务不可用时自动降级为纯BM25检索                                                      |

### P1 建议实现

| 功能模块  | 具体功能点                                            |
| ----- | ------------------------------------------------ |
| 体验优化  | 1. 无结果时给出近似搜索建议（ES suggest API）<br>2. AI服务健康检查接口 |
| 元数据补充 | 1. 文号/发文时间正则提取工具（辅助补录）                           |

### P2 后续扩展（二期）

1. 扫描件OCR解析+向量化
2. RAG生成式问答
3. 历史版本检索
4. 文档密级管控
5. 检索效果分析看板

# 文档2：架构设计（MVP版）

## 一、整体架构（混合架构）

### 核心原则

Java做业务层（适配现有系统）+ Python做AI计算层（适配AI生态）+ Kafka解耦异步任务，ES 8.6统一承载关键词/向量检索。

### 架构分层图

```
┌─────────────────────────────────────────────────────────────────┐
│ 现有政务系统（Vue3前端 + Java后端）                             │
│  ├─ 搜索页面（嵌入）：查询输入/筛选/结果展示/预览跳转           │
│  ├─ 身份认证：自研Token接口                                     │
│  └─ 权限接口：返回用户可见doc_id列表                            │
└───────────────────────┬─────────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────────┐
│ Java业务层（Spring Boot）                                        │
│  ├─ 接口层：检索API/批量导入API/健康检查API                     │
│  ├─ 权限适配：Token校验 + 调用权限接口过滤doc_id               │
│  ├─ 异步任务：Kafka生产者（新文档/修订文档向量化任务）          │
│  ├─ 缓存层：Redis（查询向量缓存/权限结果缓存）                  │
│  ├─ 降级逻辑：AI服务不可用时切换纯BM25检索                      │
│  └─ 日志层：搜索日志/操作日志写入PGsql                          │
└───────────────────────┬─────────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────────┐
│ 消息队列（Kafka）                                                │
│  ├─ 主题1：doc_vector_task（文档向量化任务）                     │
│  └─ 主题2：doc_update_task（文档更新/删除任务）                  │
└───────────────────────┬─────────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────────┐
│ Python AI层（FastAPI）                                           │
│  ├─ 消费Kafka任务：文档解析/语义分片/向量化                     │
│  ├─ 同步接口：查询文本向量化（供Java层调用）                    │
│  ├─ 模型加载：BGE-m3（FP16，GPU部署）                           │
│  └─ 健康检查接口：/health                                       │
└───────────────────────┬─────────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────────┐
│ 存储层                                                           │
│  ├─ ES 8.6：关键词检索（BM25）+ 向量检索（kNN）+ 元数据过滤     │
│  ├─ PGsql：搜索日志/批量导入进度/元数据补录信息                  │
│  ├─ Redis：查询向量缓存/权限结果缓存                            │
│  └─ 现有文件服务器：文档原文存储（仅对接预览接口）               │
└─────────────────────────────────────────────────────────────────┘
```

## 二、核心数据流

### 1. 新文档增量向量化流程

现有系统上传新文档 → 触发Java层生产Kafka任务 → Python层消费任务 → 解析/分片/向量化 → 写入ES（关联元数据+权限标签）→ 更新PGsql任务状态

### 2. 检索流程

用户输入查询+筛选条件 → Java层校验Token → 调用权限接口获取可见doc_id → 调用Python层生成查询向量（优先查Redis缓存）→ ES执行“BM25+向量kNN+元数据过滤” → Java层组装结果（关联原文预览链接）→ 返回前端

### 3. 文档更新/删除流程

现有系统修改/删除文档 → 触发Java层生产Kafka任务 → Python层消费任务 → 更新ES中文档版本/status/删除对应chunk → 标记PGsql任务完成

## 三、部署架构（内网）

1. Java服务：Docker容器部署（复用现有K8s集群）
2. Python服务：Docker容器部署（绑定GPU节点）
3. 依赖组件：复用现有ES/Redis/Kafka/PGsql，无需新增集群

# 文档3：ES 索引与数据模型设计（MVP版）

## 一、索引设计

### 1. 索引名称

`gov_doc_vector_v1`（按季度建索引，MVP阶段单索引即可）

### 2. 索引设置

```json
{
  "settings": {
    "number_of_shards": 1,  // MVP数据量小，单分片
    "number_of_replicas": 1, // 1个副本保障可用性
    "index.knn": true,       // 开启向量检索
    "index.knn.space_type": "cosine" // 余弦相似度（BGE-m3适配）
  }
}
```

## 二、Mapping设计（核心字段）

```json
{
  "mappings": {
    "properties": {
      // 1. 向量字段
      "embedding": {
        "type": "knn_vector",
        "dimension": 1024,  // BGE-m3默认维度
        "method": {
          "name": "hnsw",   // 高效近邻检索算法
          "space_type": "cosine",
          "parameters": {
            "ef_construction": 128,
            "m": 16
          }
        }
      },
      // 2. 文档基础信息（关联现有系统）
      "doc_id": { "type": "keyword" },        // 现有系统文档ID
      "doc_title": { "type": "text", "analyzer": "ik_max_word" }, // 中文分词
      "doc_content_chunk": { "type": "text", "analyzer": "ik_max_word" }, // 分片内容
      "file_type": { "type": "keyword" },     // word/pdf
      "file_path": { "type": "keyword" },     // 现有文件服务器路径
      // 3. 版本控制字段
      "doc_version": { "type": "integer" },   // 版本号
      "doc_status": { "type": "keyword" },    // active/superseded/expired
      "is_latest": { "type": "boolean" },     // 是否最新版本
      "update_time": { "type": "date" },      // 最后更新时间
      // 4. 结构化筛选字段（元数据）
      "doc_number": { "type": "keyword" },    // 文号（如国发〔2024〕1号）
      "issue_date": { "type": "date" },       // 发文时间
      "issue_dept": { "type": "keyword" },    // 发文单位
      "doc_type": { "type": "keyword" },      // 通知/决定/意见等
      "dept_id": { "type": "keyword" },       // 所属部门ID（权限过滤）
      // 5. 权限控制字段
      "creator_id": { "type": "keyword" },    // 创建人ID
      "participant_ids": { "type": "keyword" }, // 参与流程人员ID列表
      "visibility_scope": { "type": "keyword" } // 公开范围（本部门/下级/全公开）
    }
  }
}
```

## 三、数据模型核心规则

1. 一个文档会被切分为多个chunk，每个chunk都携带完整的doc_id/元数据/权限字段（冗余存储，避免跨库关联）；
2. 向量字段仅存储chunk的embedding，文档级检索通过doc_id聚合；
3. 检索时默认过滤条件：`doc_status: active AND is_latest: true`；
4. 权限过滤：通过dept_id/creator_id/participant_ids/visibility_scope组合筛选。

# 文档4：接口定义（MVP版）

## 一、统一规范

- 接口风格：RESTful
- 数据格式：JSON
- 认证方式：请求头携带现有系统Token（`X-Token: xxx`）
- 响应码：200成功/401未认证/403无权限/500服务异常/503降级中

## 二、Java业务层核心接口

### 1. 检索接口（核心）

- 接口路径：`/api/v1/search`

- 请求方式：POST

- 请求参数：
  
  ```json
  {
  "queryText": "2024年社保缴费基数调整", // 搜索词
  "filters": {                         // 结构化筛选
    "issueDateStart": "2024-01-01",    // 可选
    "issueDateEnd": "2024-12-31",      // 可选
    "issueDept": "人社局",             // 可选
    "docNumber": "人社发〔2024〕",     // 可选
    "docType": "通知"                  // 可选
  },
  "pageNum": 1,
  "pageSize": 10
  }
  ```

- 响应参数：
  
  ```json
  {
  "code": 200,
  "msg": "success",
  "data": {
    "total": 25,
    "list": [
      {
        "docId": "doc_12345",
        "docTitle": "关于2024年社保缴费基数调整的通知",
        "docNumber": "人社发〔2024〕5号",
        "issueDept": "人社局",
        "issueDate": "2024-03-15",
        "fileType": "word",
        "previewUrl": "/api/preview?docId=doc_12345", // 现有系统预览链接
        "highlights": [
          {
            "content": "2024年社保缴费基数上限调整为18000元/月，<em>下限为3600元/月</em>",
            "score": 0.98
          }
        ],
        "isLatest": true,
        "permissionTag": "本部门公开"
      }
    ],
    "isDegrade": false // 是否降级为纯关键词检索
  }
  }
  ```

### 2. 批量导入接口

- 接口路径：`/api/v1/import/batch`

- 请求方式：POST

- 请求参数：
  
  ```json
  {
  "batchId": "batch_202406", // 批次ID
  "docIds": ["doc_1001", "doc_1002"] // 现有系统文档ID列表
  }
  ```

- 响应参数：
  
  ```json
  {
  "code": 200,
  "msg": "任务已提交",
  "data": {
    "batchId": "batch_202406",
    "totalCount": 2,
    "pendingCount": 2,
    "progressUrl": "/api/v1/import/progress?batchId=batch_202406"
  }
  }
  ```

### 3. 批量导入进度查询接口

- 接口路径：`/api/v1/import/progress`

- 请求方式：GET

- 请求参数：`batchId=batch_202406`

- 响应参数：
  
  ```json
  {
  "code": 200,
  "msg": "success",
  "data": {
    "batchId": "batch_202406",
    "totalCount": 2,
    "successCount": 1,
    "failCount": 1,
    "failDocs": [
      {
        "docId": "doc_1002",
        "reason": "文件解析失败：PDF文件损坏"
      }
    ],
    "progress": 50 // 进度百分比
  }
  }
  ```

## 三、Python AI层核心接口

### 1. 查询文本向量化接口（同步）

- 接口路径：`/api/ai/vector/query`

- 请求方式：POST

- 请求参数：
  
  ```json
  {
  "text": "2024年社保缴费基数调整"
  }
  ```

- 响应参数：
  
  ```json
  {
  "code": 200,
  "msg": "success",
  "data": {
    "vector": [0.123, 0.456, ...], // 1024维向量
    "costMs": 80
  }
  }
  ```

### 2. 文档向量化任务接口（异步，Kafka消费）

- 消费主题：`doc_vector_task`

- 消息格式：
  
  ```json
  {
  "taskId": "task_123",
  "docId": "doc_1001",
  "filePath": "/data/docs/人社发〔2024〕5号.docx",
  "metadata": { // 现有系统元数据
    "docTitle": "关于2024年社保缴费基数调整的通知",
    "docNumber": "人社发〔2024〕5号",
    "issueDept": "人社局",
    "deptId": "dept_001",
    "creatorId": "user_001"
  }
  }
  ```

### 3. 健康检查接口

- 接口路径：`/api/ai/health`

- 请求方式：GET

- 响应参数：
  
  ```json
  {
  "code": 200,
  "msg": "success",
  "data": {
    "status": "running", // running/error
    "modelLoaded": true,
    "gpuUsage": "30%"
  }
  }
  ```

## 四、对接现有系统接口（只读）

### 1. 权限接口

- 接口路径：`/api/system/permission/visible-docs`

- 请求方式：GET

- 请求参数：`userId=user_001`（从Token解析）

- 响应参数：
  
  ```json
  {
  "code": 200,
  "data": {
    "docIds": ["doc_1001", "doc_1003"] // 用户可见的文档ID列表
  }
  }
  ```

### 2. 原文预览接口（预留）

- 接口路径：`/api/system/preview`
- 请求参数：`docId=doc_1001`
- 响应：跳转至现有预览页面

## 核心约束

- 人力：1人 + AI辅助
- 目标：跑通全流程，实现P0级功能
- 优先级：先跑通核心流程，再补细节/优化

## 风险与应对

1. 风险：AI服务GPU OOM → 应对：限制单批次处理文档数，增加内存监控
2. 风险：现有系统接口变更 → 应对：提前对接确认，预留适配层
