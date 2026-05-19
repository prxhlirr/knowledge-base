# ES Mapping 结构文档

> 生成时间：2026-04-01  
> ES 版本：8.x（单节点部署）  
> 数据量：`kb_document_v1` 105 docs / 2.2 MB（开发环境）

---

## 索引总览

| 索引名 | 别名 | 主分片 | 副本 | 用途 |
|--------|------|--------|------|------|
| `kb_document_v1` | `kb_document` | 1 | 0 | 主文档块存储（chunk 级） |
| `kb_doc_meta` | — | 1 | 0 | 文档元数据版本管理 |
| `kb_qa_pairs` | — | 1 | 0 | 问答对向量索引（预留） |

> **分片数量说明**：1 主分片 0 副本是当前单节点部署场景的最优配置。  
> ES 官方建议单分片上限 50GB；当前数据量离上限相差 3-4 个数量级，无需变更。  
> 扩容至多节点时，仅需热更新 `number_of_replicas`，无需重建索引。

---

## 一、主索引：`kb_document_v1`

### 1.1 Settings

```json
{
  "number_of_shards": 1,
  "number_of_replicas": 0,
  "analysis": {
    "analyzer": {
      "ik_smart":    {"type": "custom", "tokenizer": "ik_smart"},
      "ik_max_word": {"type": "custom", "tokenizer": "ik_max_word"}
    }
  }
}
```

**分词器策略（非对称）**：  
- **索引时**：`ik_max_word`（最细粒度切词，最大化召回）  
- **搜索时**：`ik_smart`（粗粒度切词，控制精度，减少噪声）

---

### 1.2 顶层字段

| 字段名 | 类型 | 索引 | 说明 |
|--------|------|------|------|
| `content` | `text` | ✅ ik_max_word | 正文内容（BM25 检索主字段） |
| `display_content` | `text` | ❌ | 含面包屑导航的展示用内容，不建倒排索引（仅存储） |
| `vector` | `dense_vector(1024)` | ✅ HNSW cosine | BGE-M3 稠密语义向量（KNN 检索主字段） |
| `colloquial_vector` | `dense_vector(1024)` | ❌ | 口语化向量（预留，当前无检索逻辑，`index:false` 节省内存） |
| `sparse_vector` | `rank_features` | ✅ | BGE-M3 稀疏词权重（配合 `rank_features+saturation` 查询） |
| `chunk_granularity` | `keyword` | ✅ | 分片粒度标识：`coarse`（大块）/ `fine`（小块） |
| `parent_chunk_id` | `keyword` | ✅ | fine chunk 的父块 ES `_id`（Small-to-Big Retrieval 核心字段） |
| `keywords` | `keyword` | ✅ | 文档关键词标签 |

---

### 1.3 `metadata` 对象字段（嵌套）

#### 文档标识与版本

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `metadata.source` | `keyword` | 文档唯一名称（对应 `kb_doc_registry.source_name`） |
| `metadata.chunk_id` | `integer` | chunk 在文档内的序号 |
| `metadata.is_latest` | `boolean` | 是否为当前最新版本（多版本并存时过滤） |
| `metadata.doc_version` | `integer` | 文档版本号 |
| `metadata.version_at` | `date(epoch_millis)` | 版本发布时间戳 |
| `metadata.updated_by` | `keyword` | 版本更新人 ID |
| `metadata.doc_type` | `keyword` | 文档类型（法规/通知/报告等） |

#### 内容结构

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `metadata.section_path` | `keyword` | 章节路径（如 `第一章/第一节`） |
| `metadata.chunk_type` | `keyword` | chunk 类型（正文/表格/附录等） |
| `metadata.quality_score` | `float` | 分块质量评分 |
| `metadata.document_number` | `keyword` | 文号（如 `国发〔2024〕1号`） |
| `metadata.search_queries` | `text` | 推荐检索问句（用于辅助 BM25 匹配） |
| `metadata.owner` | `keyword` | 文档所属人 |
| `metadata.tags` | `keyword` | 精确标签（用于聚合/过滤） |
| `metadata.tags_kw` | `keyword` | 来自 update_mapping 的补充标签字段 |
| `metadata.dynamic_meta` | `object` | 扩展元数据（`dynamic:false`，新增字段需显式 put_mapping） |

#### 权限与访问控制（ACL）

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `metadata.visibility` | `keyword` | 可见性：`PUBLIC` / `INTERNAL` / `DEPT` / `PRIVATE` / `GRANT` |
| `metadata.acl_tokens` | `keyword` | ACL 令牌列表（ES filter 权限过滤的核心字段） |
| `metadata.access_groups` | `keyword` | 访问组 |
| `metadata.uploader_id` | `keyword` | 上传者 ID（PRIVATE 权限校验） |
| `metadata.handler_user_ids` | `keyword` | 处理人 ID 列表（GRANT 扩展权限） |
| `metadata.handler_dept_l6` | `keyword` | 处理部门编码（L6 级别） |
| `metadata.visible_depts` | `keyword` | 可见部门列表 |
| `metadata.data_source` | `keyword` | 数据来源系统 |
| `metadata.owner_dept_id` | `keyword` | 所属部门 ID |

#### 部门编码体系（分级）

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `metadata.dept_l2` | `keyword` | 部门编码 L2（省级，如 `62`） |
| `metadata.dept_l4` | `keyword` | 部门编码 L4（市级，如 `6201`） |
| `metadata.dept_l6` | `keyword` | 部门编码 L6（县级，如 `620102`） |
| `metadata.dept_l9` | `keyword` | 部门编码 L9（乡镇级） |
| `metadata.dept_code_full` | `keyword` | 完整原始部门编码 |

> **部门层级设计说明**：分级存储（L2/L4/L6/L9）支持范围查询（如「查询 620102 及其所有下级部门的文档」），  
> 无需 DeptTree 递归展开，一次 terms 查询即可覆盖跨级权限场景。

#### 时间字段

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `metadata.publish_time` | `date(yyyy-MM-dd\|\|epoch_millis)` | 文档发布时间 |

---

## 二、元数据索引：`kb_doc_meta`

用途：文档版本控制中心，与 MySQL `kb_doc_registry` 形成双写，提供 ES 侧快速版本查询。

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `source_name` | `keyword` | 文档唯一名称（与主索引 `metadata.source` 对应） |
| `content_hash` | `keyword` | 文档内容 MD5（幂等上传去重依据） |
| `doc_version` | `integer` | 当前最新版本号 |
| `is_latest` | `boolean` | 是否为最新版本 |
| `updated_by` | `keyword` | 更新人 ID |
| `version_at` | `date(epoch_millis)` | 版本时间戳 |
| `chunk_count` | `integer` | 文档 chunk 总数 |
| `visibility` | `keyword` | 文档可见性 |
| `acl_tokens` | `keyword` | ACL 令牌（与主索引同步） |

---

## 三、问答对索引：`kb_qa_pairs`

用途：存储自动生成的问答对，支持问题级语义召回（QA-Style RAG 模式）。

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `question` | `text(ik_max_word)` | 问题文本 |
| `question_vector` | `dense_vector(1024, cosine)` | 问题语义向量（BGE-M3） |
| `answer_content` | `text` | 答案原文（对应条文内容） |
| `answer_chunk_id` | `keyword` | 答案所在 chunk 的 ES `_id` |
| `section_path` | `keyword` | 答案所在章节路径 |
| `source` | `keyword` | 所属文档名称 |
| `is_latest` | `boolean` | 是否最新版本 |

---

## 四、Index Template：`kb_document_template`

覆盖 `kb_document_*` 通配符，新建任意 `kb_document_vN` 版本索引时自动继承完整 Mapping 和别名。

```
匹配模式：kb_document_*
优先级：100
别名：kb_document → 当前激活版本
```

---

## 五、已知问题与注意事项

| 问题 | 状态 | 说明 |
|------|------|------|
| `colloquial_vector` HNSW 索引 | ✅ 已修复（`index:false`） | 曾建立冗余 HNSW 索引，无任何检索逻辑使用 |
| `dynamic_meta` 字段爆炸风险 | ✅ 已修复（`dynamic:false`） | 原 `dynamic:true` 可能因随机 key 写入导致超1000字段上限 |
| 副本数 yellow 状态 | ✅ 已通过 API 修复（`replicas:0`） | 单节点无法分配副本，持续 yellow 产生告警噪音 |
| Mapping 双代码维护 | ⚠️ 遗留 | `es_setup.py` 与 `rag_pipeline.py` 各维护一套 Mapping，字段定义可能漂移；建议统一以 `es_setup.py` 为单一真相来源 |
| `tags` 字段类型不一致 | ⚠️ 遗留 | `es_setup.py` 定义为 `keyword`，`rag_pipeline.py` 模板定义为 `text`；修复需确认业务用途后 reindex |
| `acl_tokens` 在 `es_setup.py` vs `rag_pipeline.py` 缺失 | ⚠️ 遗留 | `rag_pipeline.py` 的 `_update_mapping` 未包含 `acl_tokens` 字段定义 |

---

## 六、检索链路字段使用映射

```
BM25 全文检索   → content (ik_max_word/ik_smart)
KNN 语义检索    → vector (dense_vector, HNSW, cosine)
稀疏向量检索    → sparse_vector (rank_features + saturation)
权限过滤        → metadata.acl_tokens (terms filter)
版本过滤        → metadata.is_latest (term filter: true)
粒度过滤        → chunk_granularity (term filter: coarse)
父块回溯        → parent_chunk_id → ES _id 查询
Pre-Flight 探针 → metadata.document_number / metadata.source
```
