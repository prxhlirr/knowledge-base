package com.boyang.search.pipeline;

import com.boyang.search.entity.SysAiTuningConfig;
import com.boyang.search.entity.SysTenantPolicy;
import lombok.Data;

import java.util.List;
import java.util.Map;

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
    private Map<String, Object> filters;

    // ──────────────────────
    // 2. 运行时配置与策略
    // ──────────────────────
    private SysAiTuningConfig tuningConfig;
    private SysTenantPolicy tenantPolicy;
    private long startTime;

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
    private List<Map<String, Object>> candidateDocs; // 各路召回的候选文档集（未精排）
    private List<String> coreTerms;                  // 提取的核心词汇（Boost用）

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
