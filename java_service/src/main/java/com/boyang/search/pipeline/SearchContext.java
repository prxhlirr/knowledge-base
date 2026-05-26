package com.boyang.search.pipeline;

import com.boyang.search.entity.SysAiTuningConfig;
import com.boyang.search.entity.SysTenantPolicy;
import com.boyang.search.pipeline.keyword.KeywordQueryPlan;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 搜索生命周期上下文。
 * 在 Pipeline 的各个 Step 之间传递，持有从最初请求参数到最终精排结果的所有状态。
 */
@Data
public class SearchContext {

    // ──────────────────────
    // 1. 原始输入参数
    // ──────────────────────
    private String appCode;
    private String queryText;
    private int topK;
    // External return window and internal processing windows are intentionally
    // decoupled. topK is kept as a legacy alias for returnTopK.
    private int returnTopK = 50;
    private int recallTopK = 300;
    private int fusionTopK = 200;
    private int rerankTopK = 50;
    private int rerankGlobalMaxChars = 12000;
    private Map<String, Object> filters;

    /**
     * 检索模式，由前端下发，控制 EsRecallStep 选择哪条召回策略。
     * - "hybrid"  : BM25 + KNN(稠密) + Sparse(稀疏) 三路并行 + RRF 融合（默认，生产路径）
     * - "keyword" : 仅 BM25 全文检索，同时跳过 VectorFetchStep（节省 200~500ms）
     * - "semantic": KNN(稠密) + Sparse(稀疏) 双路语义召回，跳过 BM25
     * 默认值为 "hybrid"，保持对已有调用方的完全向后兼容。
     */
    private String searchMode = "hybrid";

    // ──────────────────────
    // 2. 运行时配置与策略
    // ──────────────────────
    private SysAiTuningConfig tuningConfig;
    private SysTenantPolicy tenantPolicy;
    private String resolvedIndexPattern;
    private long startTime;
    private boolean evidencePrefetchEnabled;
    private Map<String, Object> timings = new LinkedHashMap<>();
    private int literalHitCount;
    private int bm25Hits;
    private int knnHits;
    private int sparseHits;
    private int qaHitsCount;
    private int rrfCandidateCount;
    private int rerankInputCount;
    private boolean rerankDegraded;
    private boolean rerankSemaphoreRejected;
    private boolean llmSemaphoreRejected;
    private int postFilterDeniedCount;

    // ──────────────────────
    // 3. 意图梳理与文本规范化
    // ──────────────────────
    private String normalizedQuery;
    private String queryPinyin;
    private String rewrittenQuery;

    private boolean skipEmbedding;      // 降级：不查向量，只查 BM25
    private boolean navigationalBypass; // 降级：短查询跳过重排
    private boolean isShortQuery;       // 短查询标志
    private boolean skipLlmRewrite;     // 跳过长句 LLM 重写
    /** 文档类型型查询标志（如"2024年任职公示"）。
     *  此类查询用户需要的是文档内容，而非文档本身，
     *  需绕过短查询拦截，专属 HyDE 将查询扩展为人员/条文格式假设文档。 */
    private boolean isDocTypeQuery;

    /**
     * Query 意图分类（QueryNormalizeStep 写入，下游 RrfFusionStep / QaInjectionStep 读取）。
     *
     * - INFORMATIONAL : 问答型（含"如何/多少/是否/为什么"等疑问词），QA路权重提升
     * - NAVIGATIONAL  : 导航型（≤4字关键词，用户找文档），BM25 主导，QA权重压低
     * - UNKNOWN       : 其余情况（兜底，使用默认权重）
     *
     * 设计原则：复用 QueryNormalizeStep 已有的 isSemanticQuestion/navigationalBypass 判断，
     * 不增加任何新的 NLP 调用，零额外延迟。
     */
    public enum QueryIntent { INFORMATIONAL, NAVIGATIONAL, UNKNOWN }
    private QueryIntent queryIntent = QueryIntent.UNKNOWN;

    // ──────────────────────
    // 4. 向量化表征
    // ──────────────────────
    private List<Double> queryVector;
    private List<Double> originalQueryVector;
    // [Step2 优化] dual-vector 接口预取的稀疏向量，供 EsRecallStep 直接使用，跳过重复 HTTP 调用
    private Map<String, Double> querySparseVector;

    // ──────────────────────
    // 5. 中间结果与召回文档
    // ──────────────────────
    private List<Map<String, Object>> fastTrackDocs; // Pre-flight 精确命中短路返回的文档
    private List<Map<String, Object>> preflightHits; // Exact title/id hits boosted into RRF without short-circuiting
    private int preflightHitCount;
    private List<Map<String, Object>> candidateDocs; // 各路召回的候选文档集（未精排）
    private List<String> coreTerms;                  // 提取的核心词汇（Boost用）
    /** 关键词模式空格分词后的原始搜索词（由 KeywordRecallStrategy 写入）。
     *  用于下游 RrfFusionStep 做 content.contains() 精确过滤，
     *  解决 match 查询因 IK 分词导致的不精准问题。 */
    private List<String> keywordFilterTerms;
    /**
     * 关键词检索计划：空格分割出来的 requiredTerms 必须在同一文档内全部出现，
     * 但不要求出现在同一个 coarse/fine chunk 中。
     */
    private KeywordQueryPlan keywordQueryPlan;
    /** 关键词模式按词召回到的 chunk 命中，供文档级 AND 聚合使用。 */
    private List<Map<String, Object>> keywordChunkHits;
    /**
     * 关键词模式下每个搜索词命中的文档集合。
     * 召回阶段按文档折叠并分页枚举，避免先取 topN chunk 导致高频词截断漏文档。
     */
    private Map<String, Set<String>> keywordDocIdsByTerm;
    /**
     * 关键词模式下文档级命中的代表 source。
     * key 为文档键（当前优先使用 metadata.source），value 为任一命中 chunk 的 _source。
     */
    private Map<String, Map<String, Object>> keywordDocSourcesById;
    /** 关键词模式下每篇文档在召回阶段观察到的最高 ES 分数，用于后续排序兜底。 */
    private Map<String, Double> keywordDocScoresById;
    /** 覆盖全部搜索词的文档键集合，由 KeywordDocumentMatchStep 求交集后写入。 */
    private Set<String> keywordMatchedDocIds;
    /** 每篇命中文档最终用于前端展示的 coarse 分片证据。 */
    private Map<String, List<Map<String, Object>>> keywordCoarseChunksByDoc;
    /** 关键词模式聚合后的文档级命中，只保留覆盖全部 requiredTerms 的文档。 */
    private List<Map<String, Object>> keywordDocumentHits;

    /**
     * [架构重构] QA 第四路召回结果（强类型字段，替代 QaInjectionStep post-RRF 注入方案）。
     *
     * 设计原则：QA 本质是「Q2Q-optimized 检索通道」，应与 BM25/KNN/Sparse 并行召回，
     * 结果由 RrfFusionStep 作为第四路融合，而非 RRF 之后以特权分强行插队。
     * 由各 RecallStrategy 并行写入，RrfFusionStep 读取。
     */
    private List<Map<String, Object>> qaHits;        // QA 第四路召回候选（KNN 或 BM25 降级）
    private List<Map<String, Object>> answerQaHits;  // 问答专用 QA 召回，不参与普通搜索排序
    private CompletableFuture<List<Map<String, Object>>> answerQaHitsFuture;
    private long answerQaRecallMs;
    private long answerQaRecallWaitMs;
    private boolean answerQaRecallCompleted;
    private String answerQaRecallSkippedReason;

    // ──────────────────────────────────────────────────────────────────────
    // 5b. ES 召回原始响应（[P2-1 设计修复] 替换 filters.__es_raw_responses 魔法key）
    // 根因：原代码把 SearchResponse 对象塞进 filters Map（业务过滤参数容器），
    //       用 __es_raw_responses 魔法 key 传递，破坏 SearchContext 语义且无类型安全。
    // 修复：增加三个强类型专用字段，EsRecallStep 写入，RrfFusionStep 直接读取。
    // ──────────────────────────────────────────────────────────────────────
    private co.elastic.clients.elasticsearch.core.SearchResponse<Object> bm25Response;    // BM25 全文检索原始响应
    private co.elastic.clients.elasticsearch.core.SearchResponse<Object> knnResponse;     // KNN 稠密向量检索原始响应
    private co.elastic.clients.elasticsearch.core.SearchResponse<Object> sparseResponse;  // Sparse 稀疏向量检索原始响应

    // ─────────────────────────────────────────
    // 6. 最终输出结果
    // ─────────────────────────────────────────
    private List<Map<String, Object>> finalResult;

    // [P1-2 补充] BM25 原始命中数（来自 textResponse.hits().total().value()），
    // 供 RerankStep 判断是否属于高频词（命中数 > 50 时强制 Reranker 消歧）
    private long bm25TextHits = 0L;

    // ─────────────────────────────────────────────────────────────────────────
    // [P1-2 修复] BM25 平坦度（强类型字段，替换 filters Map 魔法 Key 传递方案）
    //
    // 根因：原方案通过 context.getFilters().put("_bm25_flatness_ratio", ...) 传递，
    //   存在两个致命缺陷：
    //   1) FastTrack 短路场景下 EsRecallStep 提前返回、ratio 未写入，
    //      RerankStep 读到 null → bm25IsFlat 永远 false → 平坦度检测静默失效。
    //   2) filters 为 null 时（调用方未传过滤参数）写端直接 NPE。
    // 修复：升级为强类型字段，默认 99.0（视为不平坦），彻底消除 null 风险。
    // ─────────────────────────────────────────────────────────────────────────

    /** BM25 分数平坦度（top1/top5avg），默认 99.0（不平坦，不触发强制 Reranker）*/
    private double bm25FlatnessRatio = 99.0;
}
