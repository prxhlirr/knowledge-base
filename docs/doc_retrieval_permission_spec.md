# 知识库 RAG 文档检索与向量权限控制架构规范

**版本**: V2 (引入第三方统一 IAM 结构改编)
**目标**: 解决 RAG 向量检索侧“召回坍塌”、“漏判穿透”与“过滤性能衰减”等企业级痛点，将底层权限模型重构为大厂标准的“扁平化 ACL + 双路兜底架构”。

---

## 1. 现状痛点深度剖析 (基于第一原则与大厂经验)

在当前的 `EsRecallStep` 及 `PermissionGuard` 实现中，我们面临着几个在复杂企业级知识库中**致命的架构缺陷**：

### 1.1 KNN 向量检索的数据穿透漏洞 (Data Leakage)
- **缺陷表现**：当前的 KNN DSL 中，`filter` 从句仅仅拼接了 `PUBLIC`、`INTERNAL` 以及 `dept_code_full`，**完全漏掉了 `PRIVATE` 和 `GRANT` 级别的权限过滤**。
- **后果**：无论一个文档被设置得多私密，只要不符合上述 3 个过滤条件，向量检索引擎在遍历 HNSW 相似度图时就等于“无任何限制”。一个外部用户只要发起的相关 Query 足够相似，就能毫无阻拦地把这份高度机密的 Chunk 分块用向量余弦得分给“拉”出来！

### 1.2 向量召回坍塌 (Recall Collapse)
- **大厂经验**：在早期的向量引擎设计中，很多团队采用了“后置过滤（Post-Filtering）”。即：先在 ES 里用向量拉出 `Top 100` 高度相似的文档，然后在 Java 内存再逐一排除没有权限的文档。
- **缺陷表现**：如果用户搜索的是一份自己没有权限查看、但与提问高度相关的机密文件集，可能 ES 返回的 `Top 100` 最后在 Java 侧全被过滤掉了，导致**实际返回给用户的可用内容为 0**！这就是典型的“召回截断/坍塌”。
- **结论**：**向量检索必须做“前置极速过滤（Pre-Filtering）”**。这就要求权限判断必须下潜入 ES。

### 1.3 复杂 DSL 嵌套导致的性能衰退与一致性灾难
- **缺陷表现**：为了把 `PUBLIC / DEPT / PRIVATE / GRANT` 等所有逻辑在一个查询内体现，目前 `EsRecallStep` 中塞入了深达数层的 `must / should / must_not` 树。
- **后果**：
  1. 这种逻辑在 Elasticsearch 底层的 BitSet (位图) 解析时非常缓慢，它极大地拖累了 HNSW 图索引的检索效率。
  2. 开发只要稍不留神修改一层缩进（例如上面提到的 KNN 漏写），就会直接引发最高等级的安全事故。

---

## 2. 大厂最佳实践：重构目标架构图景

结合第三方 IAM 的接入特性（第三方提供系统级身份，本地提供资源级分配），我们需要彻底扬弃“在查询时计算逻辑”的做法。转而采用**大厂标准的“写时复杂，读时极简”扁平化 ACL (Access Control List) 机制**。

### 核心演进：读时展开身份，写时展开权限

- **过去**：文档写着自己属于哪个部门；用户查询时告诉系统“我是用户 X，部门 Y”；ES 去算：Y 是不是那篇文档部门的下级或本人？逻辑嵌套在查询时。
- **现在 (扁平化架构)**：一切身份抽象为一维的 `Token`。不管是人还是文件，统统打标签。查询时只做高效的**求交集**（Intersection）。

---

## 3. 标准化改造实施细则

### 阶段一：拦截器层的前置身份展开 (Identity Pre-Loading)

网关层的 `JwtAuthInterceptor` 不再只是简单地校验 JWT 的签名，它的核心使命是向第三方查询该用户的**“所有可用身份资产”**，并转化为 ACL Tokens 数组。

> **示例**：用户张三登录（来自第三方）。
> 1. 第三方 IAM API 返回：`User(id: U9527, dept: D100_综合管理部, roles: [USER])`。
> 2. 我们通过本地 `DeptTreeService` 计算：他可以看自己的科室（D100），也可以看父级共享下来的文档（D10）。
> 3. 查本地 `kb_doc_grants`：李四曾将一个机密文档（Id: DOC-001）授权给张三。
> 
> **转换：我们将上述所有复杂业务，打成扁平扁平的 ACL 身份包**：
> `UserContext.aclTokens = ["_PUBLIC", "_INTERNAL", "DEPT:D100", "DEPT:D10", "USER:U9527", "DOC:DOC-001"]`

### 阶段二：ES 写扩展的降维重构 (Write-Time Flattening)

不再往 ES 写入那些零散的 `visibility`, `uploader_id`, `granted_users` 字段。所有写入/更新文档的操作（`DocIngestService`），全部在进 ES 之前计算出一套 `acl_tokens` 并作为一个独立的 `keyword` 数组落盘。

1. **PUBLIC 文档** → `acl_tokens`: `["_PUBLIC"]`
2. **INTERNAL 文档** → `acl_tokens`: `["_INTERNAL", "USER:U9528(上传者)"]`
3. **DEPT 文档 (比如部门 D100)** → `acl_tokens`: `["DEPT:D100", "USER:U9528"]`
4. **PRIVATE 文档** → `acl_tokens`: `["USER:U9528"]`

*（注意：一旦有任何人在页面上点了一次“分享给某人”，我们会同时更新 MySQL 的 `kb_doc_grants`，并通过 MQ 异步更新这篇文档所有相关 ES 索引的分块 `acl_tokens`。若来不及同步，将被“阶段四”的兜底机制拦截拦截）。*

### 阶段三：极简前置过滤的向量与文本双召回 (Minimalist Pre-Filter)

当业务进行 `BM25` 或 `KNN` 检索时，所有与权限有关的恶心 DSL（几十行）被重写为短短 5 行：

```json
{
  "query": {
    "bool": {
      "filter": [
        {
          "terms": {
            "acl_tokens": ["_PUBLIC", "_INTERNAL", "DEPT:D100", "DEPT:D10", "USER:U9527", "DOC:DOC-001"]
          }
        }
      ]
    }
  }
}
```
**原理**：Elasticsearch 内部处理单一 Array 到 Array 的 `terms` filter 时，是通过极其高效的 Bloom Filter 和 Bitmaps （布隆过滤器与位图）来做交集操作的，对性能损耗可以忽略不计。最关键的是，他一劳永逸地解决了 KNN 数据穿透的漏洞！

### 阶段四：Java 后置防穿透强制兜底 (Post-Filter)

考虑到 ES 刷新延迟、缓存不一致、或批量权限修改导致 ES 还未更新完：
系统在拿回 `TopK` 数据准备喂给 LLM 前，必须**遍历一次**拉取出来的来源文档 `doc_id`，丢进一次本地的服务进行强一致性校验（可走 Redis 或 MySQL）。

```java
// 兜底防御伪代码
List<DocChunk> esRecalls = esResponse.getHits();
Iterator<DocChunk> iterator = esRecalls.iterator();

while (iterator.hasNext()) {
    DocChunk chunk = iterator.next();
    // 穿过缓存命中本地最新状态
    if (!permissionGuard.canAccess(chunk.getSourceName(), userContext)) {
        log.error("拦截一起幽灵拉取：ES由于同步延迟使无权限用户查到了涉密分块！文档：{}", chunk.getSourceName());
        iterator.remove();
    }
}
```

---

## 5. 进阶安全架构：超级管理员与细粒度控制 (FGAC)

基于业务对“人员姓名脱敏”、“分段落隔离”以及“超级管理员”设定的要求，架构在扁平化 ACL 的基础上，天然支持且必须扩展以下机制：

### 5.1 超级管理员的“上帝模式” (RBAC Bypass)
当第三方 IAM 返回当前用户具有超级管理员角色（如 `ROLE:SYS_ADMIN`）时：
- **前置短路**：Java 层在组装 ES DSL 时，直接**略过** `filter` 里的 `acl_tokens` 拦截逻辑；或者在用户的 `UserContext.aclTokens` 中注入一个特殊的通配符标签（如 `_SUPER_ADMIN`）。
- **兜底放行**：Java 层的 `PermissionGuard` 遇到拥有 `_SUPER_ADMIN` 上下文的请求，直接 `return AccessResult.allow()`。
- **审计留痕**：任何以超级管理员身份发起的越权检索，必须在 `search_audit_log` 强制单独记录 `admin_bypass = true`，以应对安全合规审查。

### 5.2 细粒度控制：分片 / 段落级隔离 (Chunk-Level ACL)
我们当前的扁平化架构将权限判定从“文件级”下放到了“ES Document 级 (即 Chunk 分片)”。这意味着**在同一份文件中，不同段落可以有完全不同的 ACL 标签**！
- **应用场景**：一份涉及公司战略与核心财务数据的年度总结报告。
- **写入机制**：文档解析服务在切分 Chunk 时，可以结合大模型实体识别或固定长则，对涉密段落（如包含财务报表的 Chunk 6）单独打上 `acl_tokens: ["ROLE:FINA_ADMIN"]`；而普通总结段落打上 `acl_tokens: ["_INTERNAL"]`。
- **检索效果**：当普通员工搜索时，底层的 HNSW 向量近似计算会像没看见 Chunk 6 一样直接略过，**从物理层面上完成了部分段落的不被检索**。

### 5.3 实体级屏蔽：人员姓名等敏感词隔离 (Data Redaction)
如果是“涉密人员姓名、身份证号”等极度敏感的信息，并且要求**普通人搜不出来**，必须在写时（Ingestion Type）与读时（Query Time）结合处理，决不能依赖所谓的“查出结果再打码”（会有向量侧信道泄露风险，即用户通过打码结果推断出原文）：

- **写时孪生分片机制 (Twin Chunks Vectorization)**：
  - 对含有敏感名单的 Chunk 进行 PII（Personal Identifiable Information）脱敏提取。
  - **分片 A（明文版）**：明文向量化，带有原名“李市长”。`acl_tokens: ["_SUPER_ADMIN"]`。
  - **分片 B（脱敏版）**：打码向量化，替换为“XXX人员”。`acl_tokens: ["_INTERNAL"]`。
- **检索隔离**：普通用户拿着问题搜索时，由于只能命中“分片 B”，其召回的向量距离（Cosine Similarity）与“李市长”一词毫无关联，彻底根绝了通过检索特定姓名“套出”机密片段的可能。
- **Java 层打码**：为了防止 LLM 发生意外生成，可以在投喂进 LLM 的 Prompt 构造前，利用正则表达式扫一遍召回最终文本的黑名单词库，将敏感词全部替换为 `***`（即后置脱敏）。

1. **安全性从根本上提升**：由于将权限逻辑抽象剥离，`ES` 的查询代码不再是“谁也不敢动”的屎山，安全漏洞在物理架构上被避免（不满足 ACL 连 KNN 计算都不会参与）。
2. **性能卓越**：向量 HNSW 和 BM25 分值计算只在被证明有权限的小圈子（扁平化的集合求交结果）中进行，CPU 成本骤降。这不仅省掉了由于权限不足导致 70% 额外召回废弃所浪费掉的算力，还极大释放了第三方接口与 ES 节点的 I/O。

---

## 6. 数据源异构协同：ES与MySQL的一致性保障

在实际的业务演进中（尤其是处理存量历史文档时），经常会出现**“物理文本的切片存在 ES，但文件的单位、归属、鉴权等元数据存在关系型数据库（MySQL）”**这种分离存储的现状。如何保证刷入 ES 的权限永远是“准”的？

答案是：**以 MySQL 为单点真相源（Source of Truth），建立“异构补偿与拦截体系”。**

### 6.1 增量文档的实时拼装 (Ingest Time Injection)
对于新上传文档，系统控制流如下：
1. JWT 拦截并取得 UserID 与 DeptCode。
2. 数据准备：文件物理保存，MySQL `kb_doc_registry` 先行落盘确立文档主权。
3. 管线组装：Python 服务（或 Java 消费者）在请求大模型进行向量打块 (Embedding) 的同时，反查一次 MySQL 中此文档的公开属性。
4. 汇聚落盘：生成 `acl_tokens` 标签集，打包进带有向量的 Chunk Document 中，原子化提交给 ES。

### 6.2 存量数据的刷数回位机制 (Zero-Embedding Migration)
对于海量没有 `acl_tokens` 字段的历史存量 ES 向量分块，**绝不能重新抽取向量（会导致灾难级的显卡/接口计费和时间开销）**。大厂的做法是采用 `Update By Query` 的 Partial Update（部分更新）：
- 编写一个后台运维跑批脚本（Job）。
- 从 MySQL 把所有有效文档及其最新部门/授权名单扫出来。
- 组装成一个巨大的 `{"doc_id": "X", "target_acl": ["_INTERNAL", "DEPT:A"]}` 映射表。
- 逐批发送给 ES 侧执行 `POST /index/_update_by_query`，直接往原有 Chunk 上追增 `acl_tokens` 字段。更新纯文本结构，不会触发极度昂贵的 Vector 重新计算。

### 6.3 权限动态变更的延迟补偿 (The Ultimate Post-Filter Safety Net)
如果某篇核心机要文档，管理员在前端将其权限从“内部公开 (_INTERNAL)”突然改为了“仅自己可见 (PRIVATE)”。
在这个动作发生后：
- **异步扩散**：Java 服务通过消息队列（Kafka/RabbitMQ）向后台派发一个 ACL Update 事件，后端服务开始在几千个 ES 分片中更新其 `acl_tokens`。
- **一致性空窗期（Vulnerability Window）**：如果此时有用户正好发起搜索，ES 中的分块可能还是旧的 `_INTERNAL` 标签，从而把机密向量拉了出来！怎么办？
- **后置兜底防抖（Post-Filter）的绝对价值**：如本规范 [阶段四] 所述。即使用户通过旧标签把这篇机要文件的向量侥幸召回到了 Java 侧，在交给 LLM 前，Java 代码立刻对 `Top K` 进行了一次 `PermissionGuard` 内存核验（它直连的是刚被管理员更新完最新状态的 MySQL/Redis 源）。该核验会瞬间判定 `denied`，无情地将这个因为延迟被拖出的敏感切片剔除！

**总结**：前置（ES）负责粗粒度的**最高性能过滤**，后置（Java 护城河）负责彻底抹平“分离架构”所带来的**一致性延迟**，这就是万无一失的双轨权限体系。
