# 文档相似度推荐优化实施方案

## 1. 背景与目标

当前文档相似度推荐主要用于编辑器侧自动推荐相似文档，目标不是回答用户问题，而是判断当前草稿或已入库文档与知识库中哪些文档在主题、用途、对象、条款、场景上相近。

现有实现大体流程为：

```text
编辑器文本
 -> /api/ai/vector/long-doc 生成文档级向量
 -> kb_doc_meta.doc_vector KNN 召回
 -> 权限过滤
 -> /api/ai/rerank/document-similarity 重排
 -> 返回相似文档
```

核心问题是：现有 `kb_doc_meta` 的 mapping 和生产字段不够稳定，`doc_vector` 存在被映射为普通 `float` 数组的风险，导致文档级 KNN 不可靠。一旦文档级 KNN 失败，系统可能退化到 500 万 chunk 的主索引检索，耗时和质量都会失控。

本方案目标：

- 建立生产级文档级向量索引 `kb_doc_meta_v2`。
- 确保文档相似度推荐主路径不扫描 500 万 chunk。
- 保证普通检索、问答检索、chunk 检索不受影响。
- 历史数据可迁移、可校验、可回滚。
- 后续新文档入库能持续维护 `kb_doc_meta_v2`。

## 2. 第一性原理分析

普通检索和文档相似度推荐的目标函数不同。

普通检索需要找到具体证据片段，因此主索引必须是 chunk 级：

```text
kb_document_*：一篇文档多个 chunk，用于 BM25、KNN、Sparse、highlight、证据定位
```

文档相似度推荐需要找到相似文档，因此主索引应是文档级：

```text
kb_doc_meta_v2：一篇文档一条记录，用于文档级 KNN 和文档级排序
```

因此，`kb_doc_meta_v2` 不应该替代 `kb_document_*` 做普通检索。它可以作为文档相似推荐主索引，也可以在未来作为普通检索的文档级预召回层，但不能直接替代 chunk 级证据索引。

## 3. `kb_doc_meta_v2` 的定位

`kb_doc_meta_v2` 是文档相似度推荐专用索引，属于派生索引。

它不修改、不删除、不替换现有 `kb_document_*` 索引。普通检索仍然走现有混合检索链路：

```text
普通检索 / home 搜索 / QA 证据召回
 -> kb_document_* / kb_document_v1 / 分区索引
```

文档相似度推荐走：

```text
编辑器推荐 / 相似文档接口
 -> kb_doc_meta_read
 -> 当前指向 kb_doc_meta_v2
```

建议使用别名隔离：

```text
kb_doc_meta_read  -> kb_doc_meta_v2
kb_doc_meta_write -> kb_doc_meta_v2
```

后续升级到 `kb_doc_meta_v3` 时，只切别名，不改业务代码。

## 4. 与现有 `kb_doc_meta` 的区别

| 维度 | 现有 `kb_doc_meta` | 新建 `kb_doc_meta_v2` |
|---|---|---|
| 定位 | 辅助元数据/早期相似度索引 | 生产级文档相似度推荐主索引 |
| 粒度 | 一篇文档一条，但字段稳定性不足 | 严格一篇文档一条 latest meta |
| `doc_vector` | 可能是普通 `float` 数组 | 明确 `dense_vector`, 1024 维, `index=true`, `similarity=cosine` |
| 权限 | 字段不完整或未用于 KNN filter | 写入 `acl_tokens`，支持 ES KNN 前置权限过滤 |
| 版本 | 可能不完整 | `doc_version`、`is_latest`、`content_hash` 完整 |
| 文档标识 | 可能主要依赖文件名 `source` | 使用 `doc_id`、`content_hash`、`source` 多重标识 |
| 元数据 | 字段较少 | 增加 `title`、`summary`、`doc_type`、`data_source`、`chunk_count` |
| 检索稳定性 | KNN 失败可能 fallback 到 chunk | 正常路径必须直接文档级 KNN |
| 回滚 | 原地修改风险高 | 通过别名切换回滚 |

新建 v2 的原因：

1. ES 字段类型不能安全原地修改。若 `doc_vector` 已经是 `float`，不能直接改成 `dense_vector`。
2. 避免影响现有功能。新索引旁路建设，校验通过后只切相似推荐别名。
3. 补齐生产字段。文档相似度推荐需要权限、版本、文档标识、摘要等字段，不能只存一个向量。
4. 支持蓝绿迁移和回滚。

## 5. 目标 Mapping

建议 `kb_doc_meta_v2` mapping 至少包含：

```json
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0
  },
  "mappings": {
    "properties": {
      "doc_id": { "type": "keyword" },
      "doc_version": { "type": "integer" },
      "content_hash": { "type": "keyword" },
      "source": { "type": "keyword" },
      "source_name": { "type": "keyword" },
      "title": {
        "type": "text",
        "fields": {
          "keyword": { "type": "keyword", "ignore_above": 256 }
        }
      },
      "summary": { "type": "text", "index": false },
      "doc_type": {
        "type": "keyword"
      },
      "data_source": { "type": "keyword" },
      "chunk_count": { "type": "integer" },
      "is_latest": { "type": "boolean" },
      "acl_tokens": { "type": "keyword" },
      "visibility": { "type": "keyword" },
      "owner_dept_id": { "type": "keyword" },
      "updated_at": { "type": "date", "format": "epoch_millis" },
      "doc_vector": {
        "type": "dense_vector",
        "dims": 1024,
        "index": true,
        "similarity": "cosine"
      }
    }
  }
}
```

第一阶段只强制 `doc_vector`，后续可扩展：

```text
title_vector
lead_vector
key_vector
```

这些字段用于多路文档级召回，暂不作为 P0 必须项。

## 6. 历史数据迁移方案

历史数据迁移不重新跑 embedding，优先复用现有 chunk 的 `vector` 字段。

迁移流程：

```text
创建 kb_doc_meta_v2
 -> 扫描 kb_document_* 中 latest fine chunk
 -> 按 doc_id/content_hash/source 分组
 -> mean pooling + L2 normalize 得到 doc_vector
 -> 汇总权限与元数据
 -> bulk 写入 kb_doc_meta_v2
 -> 校验
 -> 切换 kb_doc_meta_read/write 别名
```

分组优先级：

```text
metadata.doc_id
 -> content_hash
 -> metadata.source + data_source
```

不建议只用 `source`，因为同名文件、跨部门同名文件、版本更新都会产生覆盖风险。

权限回填优先级：

```text
chunk.acl_tokens
 -> MySQL 文档授权表
 -> 默认 fail-closed，不写入 _PUBLIC
```

如果一个文档下多个 chunk 的 `acl_tokens` 不一致，需要记录异常。第一阶段可以取并集以避免漏权限，但上线前必须抽样核对是否会扩大授权范围。更严格策略是以 MySQL 文档授权为准。

迁移期间新入库文档处理：

- 方案 A：新入库双写旧 `kb_doc_meta` 和新 `kb_doc_meta_v2`。
- 方案 B：迁移期间仍写旧索引，切换前跑增量补偿任务。

推荐方案 A，但如果改动成本高，可先用方案 B。

## 7. 后续文档入库保障

`kb_doc_meta_v2` 是派生索引，不能反向影响主文档入库链路。

推荐入库链路：

```text
解析文档
 -> 切 chunk
 -> 生成 chunk vector
 -> 写 kb_document_* chunk 索引
 -> 计算 doc_vector
 -> 写 kb_doc_meta_write
 -> 更新 MySQL 入库状态
```

关键保障：

1. 使用写别名  
   入库代码写 `kb_doc_meta_write`，不要硬编码 `kb_doc_meta_v2`。

2. 幂等写入  
   `_id` 建议使用：

```text
doc_id + "_" + doc_version
```

或使用稳定 `content_hash`。不建议只用文件名。

3. 版本控制  
   新版本写入后，新版本 `is_latest=true`，旧版本 `is_latest=false`。

4. 权限同步  
   `kb_doc_meta_v2` 必须与 chunk 写入一致的 `acl_tokens`、`visibility`、`owner_dept_id`、`data_source`。

5. 失败补偿  
   chunk 写入成功但 doc_meta 写入失败时，不应阻断主入库。应记录 outbox 或补偿任务，后台重试。

6. 定期巡检  
   每天或每小时校验：

```text
latest 文档数
kb_doc_meta_v2 latest 数
缺失 doc_meta 的 doc_id
doc_vector 维度异常
acl_tokens 为空
同一 doc_id 多条 latest
```

最终一致性规则：

```text
每一篇可检索 latest 文档，都必须有且仅有一条 latest doc_meta。
```

## 8. 查询链路优化

编辑器推荐目标链路：

```text
编辑器文本
 -> 稳定采样
 -> /api/ai/vector/long-doc
 -> kb_doc_meta_read KNN，含 ACL filter
 -> top20 文档候选
 -> 文档级 rerank，超时降级
 -> top5 返回
```

KNN 必须增加前置过滤：

```text
is_latest = true
acl_tokens in user_acl_tokens
data_source 可选
doc_type 可选
```

后置 `PermissionGuard` 保留，但只作为兜底。正常情况下 `denied_count` 应接近 0。

候选窗口建议：

```text
topK = 5
fetchSize = 20
numCandidates = 100 ~ 150
rerank input <= 20
```

不建议默认 top50 rerank。编辑器推荐是自动触发功能，SLA 优先级高于极限召回。

## 9. 耗时目标与解释

必须区分两种场景。

### 9.1 已入库文档找相似文档

如果输入是已入库文档，并且可以直接复用其预计算 `doc_vector`：

```text
读取 doc_vector：几毫秒到几十毫秒
ES doc-level KNN：50 ~ 300ms
权限过滤/组装：几十毫秒
总耗时目标：100 ~ 500ms
```

这是生产上应追求的毫秒级路径。

### 9.2 编辑器实时草稿推荐

如果输入是用户正在编辑的新文本，系统需要现场生成向量：

```text
文本采样：< 20ms
long-doc embedding：300ms ~ 1.5s
doc-level KNN：50ms ~ 300ms
top10/top20 rerank：500ms ~ 2s
```

目标：

| 模式 | 目标耗时 |
|---|---:|
| 不 rerank，只文档级向量召回 | 300ms ~ 1s |
| top10 rerank | 1s ~ 2s |
| top20 rerank | 1.5s ~ 3s |
| fallback 到 chunk | 异常路径，需报警 |

如果生产要求“编辑器实时推荐也必须毫秒级”，则不能每次现场跑 long-doc embedding 和 rerank。需要做：

- 草稿向量增量缓存。
- 只对变化段落做增量向量化。
- 默认先返回向量召回，rerank 异步刷新。
- 或只在用户停顿较久、手动点击时触发 rerank。

## 10. 普通检索是否使用 `kb_doc_meta_v2`

不建议普通检索主路径直接使用 `kb_doc_meta_v2` 替代 `kb_document_*`。

原因：

- 普通检索需要 chunk 级证据和高亮。
- 文档级向量无法精确定位条款、金额、时间、名单。
- 长文 mean vector 容易出现主题相似但答案无关。
- 权限将来可能细到 chunk 级，文档级索引无法表达。

可选增强方案：

```text
query
 -> kb_doc_meta_v2 找 topN 相似文档
 -> 限定 doc_id 范围
 -> kb_document_* 做 BM25/KNN chunk 检索
 -> rerank
 -> 返回具体证据片段
```

这可以作为后续优化，用于缩小 500 万 chunk 的搜索范围，但不是 P0。

## 11. 观测指标

文档相似推荐必须单独记录以下字段：

```text
query_chars
segments
vector_ms
knn_ms
permission_ms
rerank_ms
total_ms
candidate_count
accessible_candidate_count
denied_count
below_threshold_count
fallback_used
cache_hit
rerank_used
rerank_timeout
resolved_meta_index
```

报警规则建议：

```text
fallback_used > 1%
denied_count 持续 > 0
P95 total_ms > 3000
knn_ms P95 > 800
rerank_timeout > 5%
doc_meta 缺失率 > 0.5%
```

## 12. 验收标准

P0 验收：

- `GET kb_doc_meta_read/_mapping` 中 `doc_vector.type=dense_vector`。
- `doc_vector.dims=1024`。
- `fallback_used=false` 为常态。
- 已入库文档相似推荐可在 500ms 内返回。

P1 验收：

- 普通用户请求无越权结果。
- `denied_count` 接近 0。
- 编辑器推荐 P95 <= 3s。
- rerank 超时仍返回 `code=200`，使用 vectorScore 降级。

P2 验收：

- top5 中至少 3 条与人工标注相似主题一致。
- 长文推荐不只受尾部段落影响。
- 不同文档类型、公示/通知/法规/方案均有可接受结果。

## 13. 回滚方案

回滚不删除数据，只切别名：

```text
kb_doc_meta_read  -> 旧 kb_doc_meta
kb_doc_meta_write -> 旧 kb_doc_meta
```

如果代码已经改为只读别名，回滚无需重新发布。`kb_doc_meta_v2` 保留用于排查。

普通检索不受回滚影响，因为普通检索未切换到 `kb_doc_meta_v2`。

## 14. 实施顺序

1. 新建 `kb_doc_meta_v2` mapping。
2. 编写历史数据回填脚本。
3. 回填历史数据。
4. 校验 mapping、数量、权限、向量维度。
5. Java 相似推荐改读 `kb_doc_meta_read`。
6. KNN 增加 ACL 和 latest 前置 filter。
7. 增加耗时与 fallback 观测字段。
8. 切换读别名。
9. 新入库链路写 `kb_doc_meta_write`。
10. 压测和人工样例评估。
11. 根据结果决定是否开启 rerank、top10 或 top20。

## 15. 最终结论

`kb_doc_meta_v2` 的核心价值是把文档相似度推荐从 500 万 chunk 的重检索，改成文档数级别的轻检索。

它不影响现有普通检索；普通检索继续依赖 `kb_document_*` 的 chunk 级证据索引。

优化后的正常路径应是：

```text
已入库文档相似推荐：100 ~ 500ms
编辑器实时推荐，不 rerank：300ms ~ 1s
编辑器实时推荐，top10/top20 rerank：1s ~ 3s
fallback 到 chunk：异常路径，必须报警
```

如果生产要求所有编辑器实时推荐都达到毫秒级，则必须进一步引入草稿向量缓存、增量向量化和异步 rerank，而不能每次同步现场 long-doc embedding + rerank。
