package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("sys_ai_tuning_config")
public class SysAiTuningConfig {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * Embedding模型在 AI 节点上的本地加载绝对路径
     */
    private String modelPath;

    /**
     * Cross-Encoder 重排模型在 AI 节点上的本地加载绝对路径
     */
    private String rerankerPath;

    /**
     * 仅在 CPU 模式下生效的推断并行线程数
     */
    private Integer cpuThreads;

    /**
     * 是否启用 FP16 半精度加速 (建议仅在 GPU 模式开启)
     */
    private Boolean useFp16;

    /**
     * 混合检索中：传统 BM25 文本匹配得分的融合权重占比
     */
    private BigDecimal bm25Weight;

    /**
     * 混合检索中：Vector 语义相似度得分的融合权重占比
     */
    private BigDecimal vectorWeight;

    /**
     * RRF (Reciprocal Rank Fusion) 并榜算法的候选召回窗口深度
     */
    private Integer rrfWindowSize;

    /**
     * 是否开启紧急故障熔断：开启后全量请求将跳过 AI 微服务降级为纯文本检索
     */
    private Boolean circuitBreakerEnabled;

    public Boolean getCircuitBreakerEnabled() {
        return circuitBreakerEnabled;
    }

    /**
     * ES 检索时标题/来源字段的增强权重 (Title Boost)
     */
    private BigDecimal titleBoost;

    /**
     * ES 原始分数归一化基准线 (ES Norm Base)
     */
    private BigDecimal esNormBase;

    /**
     * 每次检索送往 AI 精排的最大文档数量 (Rerank Limit)
     */
    private Integer rerankLimit;

    /**
     * 送往精排的单篇文档文本截断长度 (Rerank Max Chars)
     */
    private Integer rerankMaxChars;

    public BigDecimal getBm25Weight() { return bm25Weight; }
    public BigDecimal getVectorWeight() { return vectorWeight; }
    public BigDecimal getTitleBoost() { return titleBoost; }
    public BigDecimal getEsNormBase() { return esNormBase; }
    public Integer getRerankMaxChars() { return rerankMaxChars; }

    /**
     * ES 检索时 Text 底座容忍的超时间隔(ms)
     */
    private Integer esQueryTimeout;

    /**
     * 向量模型生成 Embedding 的接口硬超时(ms)
     */
    private Integer embeddingTimeout;

    /**
     * 深度重排 Cross-Encoder 接口硬超时(ms)
     */
    private Integer rerankTimeout;

    /**
     * 触发“纯词汇抢跑”免大模型运算的极限长度
     */
    private Integer lexicalFastPathMaxLength;

    /**
     * 触发“短词免重排”熔断的极限长度
     */
    private Integer adaptiveBreakerMaxLength;

    /**
     * 触发重排熔断的 RRF 融合分数下限警戒线
     */
    private BigDecimal breakerRrfThreshold;

    /**
     * 触发重排熔断的 ES 原始召回分数警戒线
     */
    private BigDecimal breakerRawScoreThreshold;

    public BigDecimal getBreakerRawScoreThreshold() { return breakerRawScoreThreshold != null ? breakerRawScoreThreshold : new BigDecimal("0.04"); }

    /**
     * 触发高分段碾压局豁免重排的 ES 原始分数阈值
     */
    private BigDecimal highScoreExemptionThreshold;

    public BigDecimal getHighScoreExemptionThreshold() { return highScoreExemptionThreshold != null ? highScoreExemptionThreshold : new BigDecimal("12.0"); }

    /**
     * 该配置记录的最后一次持久化更新时间
     */
    private LocalDateTime updatedTime;

    /**
     * 动态公文元数据提取规则引擎 (JSON Array: [{key, label, regex}])
     */
    private String metaExtractRules;

    public String getMetaExtractRules() { return metaExtractRules; }

    /**
     * 最大语义分块字符数
     */
    private Integer maxChunkSize;

    /**
     * 最小向上合并字符数
     */
    private Integer minChunkSize;

    /**
     * 目标语义切分字数
     */
    private Integer targetChunkSize;

    /**
     * 相邻分片重叠字符数
     */
    private Integer overlapSize;

    /**
     * 兜底滑动窗口大小
     */
    private Integer slidingWindowSize;

    /**
     * 兜底滑动步长
     */
    private Integer slidingWindowStep;

    /**
     * 允许入库的最低切片质量分 (0.0~1.0)
     */
    private BigDecimal minQualityScore;

    public Integer getMaxChunkSize() { return maxChunkSize != null ? maxChunkSize : 500; }
    public Integer getMinChunkSize() { return minChunkSize != null ? minChunkSize : 100; }
    public Integer getTargetChunkSize() { return targetChunkSize != null ? targetChunkSize : 350; }
    public Integer getOverlapSize() { return overlapSize != null ? overlapSize : 50; }
    public Integer getSlidingWindowSize() { return slidingWindowSize != null ? slidingWindowSize : 400; }
    public Integer getSlidingWindowStep() { return slidingWindowStep != null ? slidingWindowStep : 350; }
    public BigDecimal getMinQualityScore() { return minQualityScore != null ? minQualityScore : new BigDecimal("0.30"); }

    /**
     * KNN 向量召回时 HNSW 图的候选遍历数（numCandidates）。
     * 值越大召回率越高但越慢；默认 500，大索引（>5万chunk）建议调至 800~1200。
     * 修复：P2 #4 numCandidates 硬编码。
     */
    private Integer knnNumCandidates;

    public Integer getKnnNumCandidates() { return knnNumCandidates != null ? knnNumCandidates : 500; }

    /**
     * Out-of-Corpus 语料外截断阈值（Reranker 已运行时使用）。
     * 当最高分低于此值时，认为所有结果均超出知识库范围，返回空。
     * 默认 0.12（比原来 0.20 更宽松，避免全量弱相关时误截断）。
     * 修复：P0 #14 相对分被当绝对分用。
     */
    private BigDecimal outOfCorpusThreshold;

    public double getOutOfCorpusThreshold() {
        return outOfCorpusThreshold != null ? outOfCorpusThreshold.doubleValue() : 0.12;
    }

    /**
     * Out-of-Corpus 语料外截断阈值（Reranker 未运行/超时时使用）。
     * 仅有 RRF 分时，置信度低，使用更低的阈值（默认 0.08）。
     */
    private BigDecimal outOfCorpusThresholdNoRerank;

    public double getOutOfCorpusThresholdNoRerank() {
        return outOfCorpusThresholdNoRerank != null ? outOfCorpusThresholdNoRerank.doubleValue() : 0.08;
    }
    /**
     * ColBERT 单文档 Veto 阈值（P2.12 集体 Veto 信号需要）。
     * ColBERT 打分低于此阈值认为该文档与查询无关（被 Veto）。
     * 当超过 70% 候选被 Veto 时，系统返回空结果。
     * 默认 0.35（ColBERT MaxSim 评分范围 [0,1]）。
     */
    private BigDecimal colbertVetoThreshold;

    public double getColbertVetoThreshold() {
        return colbertVetoThreshold != null ? colbertVetoThreshold.doubleValue() : 0.35;
    }

    /**
     * Quality Breaker 绝对下限门槛（qualityBreakerFloor）。
     * 当 topScore×0.20 低于此值时，以此值为最终截断线。
     * 值过高会误杀弱语义正确结果（如文学/冷门词），过低会放入无关噪音。
     * 默认 0.06：Out-of-Corpus 通过后，只做相对清洗，不设高绝对门槛。
     * 可在管理界面直接调整，无需重新发版。
     */
    private BigDecimal qualityBreakerFloor;

    public double getQualityBreakerFloor() {
        return qualityBreakerFloor != null ? qualityBreakerFloor.doubleValue() : 0.06;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 检索管道硬编码治理：将 SearchService 中散落的魔法值迁移至此处统一热调
    // 所有 getter 含默认值，DB 字段为 null 时行为与原代码完全相同
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * QA 对话型语义准入阈值（默认 0.82）。
     * 含义：QA 检索时，候选文档的 BM25 相似度必须高于此值才被纳入。
     * 调低 → 召回更多（精度降），调高 → 更严格（召回降）。
     */
    private BigDecimal qaSimThreshold;

    public double getQaSimThreshold() {
        return qaSimThreshold != null ? qaSimThreshold.doubleValue() : 0.82;
    }

    /**
     * 口语化（Colloquial）向量语义准入阈值（默认 0.78）。
     * 与 qaSimThreshold 类似，用于控制口语化检索通道的入围严格度。
     */
    private BigDecimal colloquialSimThreshold;

    public double getColloquialSimThreshold() {
        return colloquialSimThreshold != null ? colloquialSimThreshold.doubleValue() : 0.78;
    }

    /**
     * KNN 向量检索结果的最低相似度门控（默认 0.15）。
     * 低于此阈值的 KNN 命中被过滤，防止低质量语义匹配污染 RRF 融合结果。
     * 注意：调整此值会影响所有 KNN 通道（coarse/QA/sparse）。
     */
    private BigDecimal knnMinSim;

    public double getKnnMinSim() {
        return knnMinSim != null ? knnMinSim.doubleValue() : 0.15;
    }

    /**
     * QA 检索通道 KNN 专用候选数（默认 50）。
     * 独立于全局 knnNumCandidates，允许 QA 场景单独调整精度/性能平衡。
     */
    private Integer qaKnnCandidates;

    public int getQaKnnCandidates() {
        return qaKnnCandidates != null ? qaKnnCandidates : 50;
    }

    /**
     * 口语化检索通道 KNN 专用候选数（默认 80）。
     * 通常高于 QA 候选数，因为口语化查询的语义边界更模糊，需要更大召回窗口。
     */
    private Integer colloquialKnnCandidates;

    public int getColloquialKnnCandidates() {
        return colloquialKnnCandidates != null ? colloquialKnnCandidates : 80;
    }

    /**
     * HyDE（Hypothetical Document Embedding）质量门控阈值（默认 0.75）。
     * HyDE 生成的假设文档向量与原始查询向量的相似度必须高于此值，
     * 才使用 HyDE 增强向量替换原查询向量；否则降级为原始向量查询，防止 LLM 幻觉污染。
     */
    private BigDecimal hydeMinSim;

    public double getHydeMinSim() {
        return hydeMinSim != null ? hydeMinSim.doubleValue() : 0.75;
    }

    /**
     * 政务查询噪词表（TEXT，逗号分隔）。
     * 用于 extractCoreTerms：过滤掉停用词后提取查询锚词，锚词用于 BM25 必须匹配约束。
     * 重要：「要求」「通知」「规定」等实质性政务词不能放入此表，
     *       否则会破坏短查询（如「总体要求」）的锚词提取，导致无结果。
     * 修改此字段后 Redis 缓存立即刷新，无需重启服务。
     */
    private String noiseWords;

    public java.util.List<String> getNoiseWordList() {
        if (noiseWords == null || noiseWords.trim().isEmpty()) return java.util.Collections.emptyList();
        return java.util.Arrays.asList(noiseWords.split(","));
    }

    /**
     * RRF 平滑因子 k（复用 rrfWindowSize 字段，默认 60）。
     * getRrfK() 是 rrfWindowSize 的语义别名：RRF 窗口深度即为平滑因子 k。
     * 不新增字段，直接读取 rrfWindowSize；为零或 null 时回退到经典值 60。
     */
    public int getRrfK() {
        return rrfWindowSize != null && rrfWindowSize > 0 ? rrfWindowSize : 60;
    }
}
