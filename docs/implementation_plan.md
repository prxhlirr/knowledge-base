# 搜索精准度提升实施方案

针对目前“短搜索词（如：结婚2年）”在混合检索中由于关键词不完全匹配导致的语义评分受限、排序不合理问题，以下是三种不同架构方向的详细实施方案。

## 方案一：HyDE（假设性文档嵌入）+ Query 重写（推荐，适合短文本扩展）

**思想**：对于极短的查询词，利用轻量级 LLM 生成一段“假设性的理想文档或回答”，然后用这段生成的长文本去提取向量，再进行向量检索。这样能大幅丰富语义特征，使得纯语义检索（即使没有关键词命中）也能找到高相关文档。

### 实施步骤：
1. **AI 服务端 (`ai_service`) 改造**：
   - 增加一个新的接口 `/v1/hyde/rewrite`。
   - 接入一个轻量级且极快速的 LLM（如 Qwen2.5-1.5B 或者是专门的改写微调模型）。
   - 编写 Prompt：`"根据用户的简短搜索词，写一段简短的百科解释或相关文档片段。搜索词：{query}"`。
2. **Java 服务端 (`java_service`) 改造**：
   - 修改 [SearchService.java](file:///e:/project/AI/knowledge-base/java_service/src/main/java/com/boyang/search/service/SearchService.java) 中的相关逻辑。并在 [SysAiTuningConfig](file:///e:/project/AI/knowledge-base/java_service/src/main/java/com/boyang/search/entity/SysAiTuningConfig.java#11-174) 中增加一个参数 `hydeEnableMinLength`（例如查询长度 <= 5 时开启）。
   - 如果查询词很短，先调用 `/v1/hyde/rewrite` 拿到扩展文本，然后再拿扩展文本去调用原来的 `/v1/embeddings` 生成向量。
   - ES 检索时，关键词检索依然使用原查询词 [query](file:///e:/project/AI/knowledge-base/ai_service/main.py#100-130)，但向量检索使用扩展文本的 `vector`。
3. **优势与代价**：
   - **优势**：完美解决短词语义单薄的问题，不破坏现有评分架构。
   - **代价**：多了一次 LLM 推理的开销（会增加 200ms - 500ms 延迟）。

---

## 方案二：Rerank-Only 评分机制（“一票否决”制，最为果断）

**思想**：抛弃现有的 `BM25得分 * Ratio + Rerank得分 * (1-Ratio)` 融合机制。将 ES（BM25 + 向量）仅作为“召回（Recall）”阶段，找出的 Top 20 候选文档，其最终排序得分 **100% 由 Cross-Encoder Rerank 模型决定**。

### 实施步骤：
1. **彻底解耦召回与排序**：
   - ES 查询仅用于获取包含潜在相关内容的文档列表配置，不再纠结 ES 分数在最终列表中的比重。
2. **Java 服务端核心代码精简 ([SearchService.java](file:///e:/project/AI/knowledge-base/java_service/src/main/java/com/boyang/search/service/SearchService.java))**：
   - 移除所有的 `esNormBase` 动态归一化或插值计算。
   - 对 ES 返回的候选列表直接调用 [Rerank](file:///e:/project/AI/knowledge-base/ai_service/main.py#67-70) 模型。
   - 将最终的 `finalDisplayScore` 直接等同于 Rerank 算出的语义匹配度分数（例如经过 Sigmoid 映射后的 0~1 分数转为百分比）。
3. **阈值过滤**：
   - 设置一个硬性的 Rerank 及格线（例如 `rerank_min_score_threshold`，如 10%）。低于该分数的直接丢弃（即一票否决）。
4. **优势与代价**：
   - **优势**：架构极度简化，只要 Rerank 模型足够聪明，最终排序一定符合人类直觉，“词命中但语义不符”的文档会被果断踢出。
   - **代价**：极度依赖 Rerank 模型的能力；且要求召回阶段必须要能把 Target 文档拿出来（如果目标文档连 ES Top20 都进不了，Rerank 也就无能为力）。

---

## 方案三：BGE-M3 晚期交互模型 / ES 同义词库（工程化硬手段）

**思想**：从底层特征提取入手。不用传统单向量（Dense Vector），改用多向量晚期交互（Late Interaction）模型，或者直接在 ES 层面补齐关键词缺失。

### 实施步骤：

#### 方案 3.1 BGE-M3 多模态/晚期交互
1. **AI 服务端 (`ai_service`) 改造**：
   - 将当前的 Embedding 模型替换为 `BAAI/bge-m3`（或类似 ColBERT 模型）。
   - 该模型不仅输出全局特征向量，还会输出词汇级权重稀疏特征（Sparse Vector）。
2. **ES 存储与查询改造**：
   - 数据导入环节，需要同时存储 Dense 向量和 Sparse 向量。
   - 查询环节，由于具备词汇感知能力，哪怕是“成婚”和“结婚”，也会在 Sparse 表示上有很高的纠缠度，直接从底层解决语义与词汇的融合。

#### 方案 3.2 Elasticsearch 同义词库
1. **ES 配置修改**：
   - 维护一个业务同义词表（`synonyms.txt`），包含例如 `结婚,成婚,嫁娶` 等映射。
   - 修改 IK 分词器配置，将其与 `synonym_graph` 过滤器结合。
2. **优势与代价**：
   - **优势**：从底层解决，效率极高，不增加额外的调用链路重写逻辑。
   - **代价（同义词）**：需要人工持续维护同义词库，通用性较差。
   - **代价（BGE-M3）**：升级模型不仅要求重构向量维度的字段，还需要把**现有库里的所有文档重新灌库（Re-index）**一遍提取新向量。

---

## 总结与建议行动路径

根据你系统的当前代码状态：

*   **如果追求快速见效且逻辑最简洁**：强烈建议先实施 **方案二（Rerank-Only）**。你当前已经有了 Rerank 接口，只需要在 [SearchService](file:///e:/project/AI/knowledge-base/java_service/src/main/java/com/boyang/search/service/SearchService.java#38-851) 中将公式修改为只认 Rerank 分数即可。
*   **如果方案二实施后发现召回不出目标文档**：说明短词向量确实太弱，此时可以叠加实施 **方案一（HyDE）**。
*   **方案三**代价过高（需重算所有数据），建议作为长期终极优化目标。

请问你倾向于先从哪一个方案入手？在确认后，我将为你拆分为具体的代码任务清单。
