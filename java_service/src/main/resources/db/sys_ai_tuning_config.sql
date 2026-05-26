-- ====================================================================
-- sys_ai_tuning_config 全量建表脚本（含所有从 V2~V6 migration 汇总的字段）
-- 设计说明：
--   Flyway 在离线部署中未被启用，V2~V6 的 ALTER TABLE 永远不会被自动执行。
--   将所有字段直接合并到 CREATE TABLE 中，确保离线全新数据库开箱即用。
--   字段默认值已按「离线无 GPU / 数据量大」场景调整，避免检索被误截断。
-- ====================================================================

CREATE TABLE IF NOT EXISTS sys_ai_tuning_config (
    id                                  SERIAL          PRIMARY KEY,

    -- 模型路径（容器部署时使用 /app/models 路径）
    model_path                          VARCHAR(255)    DEFAULT '/app/models/bge-m3',
    reranker_path                       VARCHAR(255)    DEFAULT '/app/models/bge-reranker-v2-m3',

    -- 推理设备
    device                              VARCHAR(50)     DEFAULT 'cpu',
    cpu_threads                         INT             DEFAULT 4,
    use_fp16                            BOOLEAN         DEFAULT true,
    batch_size                          INT             DEFAULT 16,
    max_length_cpu                      INT             DEFAULT 128,
    max_length_gpu                      INT             DEFAULT 512,

    -- 混合检索权重
    bm25_weight                         NUMERIC(5,2)    DEFAULT 0.30,
    vector_weight                       NUMERIC(5,2)    DEFAULT 0.70,
    rrf_window_size                     INT             DEFAULT 60,
    recall_top_k                        INT             DEFAULT 300,
    fusion_top_k                        INT             DEFAULT 200,
    rerank_top_k                        INT             DEFAULT 50,
    rerank_global_max_chars             INT             DEFAULT 12000,

    -- 全局熔断开关（true=跳过 AI 降级纯 BM25，仅故障时临时启用）
    circuit_breaker_enabled             BOOLEAN         DEFAULT false,

    -- 精排控制
    rerank_limit                        INT             DEFAULT 15,
    rerank_max_chars                    INT             DEFAULT 500,

    -- ES 分数归一化基准（normalized = raw / (raw + base)）
    es_norm_base                        NUMERIC(10,2)   DEFAULT 20.00,

    -- 超时配置（毫秒）
    es_query_timeout                    INT             DEFAULT 1500,
    embedding_timeout                   INT             DEFAULT 1500,
    rerank_timeout                      INT             DEFAULT 1500,

    -- 短词检索熔断
    lexical_fast_path_max_length        INT             DEFAULT 4,
    adaptive_breaker_max_length         INT             DEFAULT 4,
    breaker_rrf_threshold               NUMERIC(10,4)   DEFAULT 0.0200,
    breaker_raw_score_threshold         NUMERIC(10,4)   DEFAULT 1.0000,
    high_score_exemption_threshold      NUMERIC(10,4)   DEFAULT 12.0000,

    -- ────────────────────────────────────────────────────────────────
    -- [关键阈值] 检索结果质量过滤
    -- colbert_veto_threshold：
    --   原 Java 硬编码默认值 0.35，对 ≤4字短词严苛（BGE-M3 短词正常得分 0.10~0.25）。
    --   改为 0.18，大幅降低 CollectiveVeto 误杀概率。
    --   注：navigationalBypass 查询已在 RerankStep.java 层完全豁免 CollectiveVeto。
    colbert_veto_threshold              NUMERIC(5,4)    DEFAULT 0.1800,

    -- Out-of-Corpus 截断（Reranker 已运行）：topScore < 此值 → 认为语料外 → 返回空
    out_of_corpus_threshold             NUMERIC(5,4)    DEFAULT 0.0500,

    -- Out-of-Corpus 截断（Reranker 未运行/超时）：无精排时置信度低，阈值极小
    out_of_corpus_threshold_no_rerank   NUMERIC(5,4)    DEFAULT 0.0050,

    -- 结果集相对质量下限（topScore × 0.20 低于此值时以此值截断）
    quality_breaker_floor               NUMERIC(10,4)   DEFAULT 0.0600,

    -- UI 层最高分压制
    truthful_ui_max_score_limit         NUMERIC(10,4)   DEFAULT 0.1500,
    truthful_ui_ceiling                 NUMERIC(10,4)   DEFAULT 0.7500,
    -- ────────────────────────────────────────────────────────────────

    -- 语义通道阈值
    qa_sim_threshold                    NUMERIC(5,4)    DEFAULT 0.8200,
    colloquial_sim_threshold            NUMERIC(5,4)    DEFAULT 0.7800,
    knn_min_sim                         NUMERIC(5,4)    DEFAULT 0.1500,
    qa_knn_candidates                   INT             DEFAULT 50,
    colloquial_knn_candidates           INT             DEFAULT 80,
    knn_num_candidates                  INT             DEFAULT 500,
    hyde_min_sim                        NUMERIC(5,4)    DEFAULT 0.7500,

    -- 分块参数
    max_chunk_size                      INT             DEFAULT 500,
    min_chunk_size                      INT             DEFAULT 100,
    target_chunk_size                   INT             DEFAULT 350,
    overlap_size                        INT             DEFAULT 50,
    sliding_window_size                 INT             DEFAULT 400,
    sliding_window_step                 INT             DEFAULT 350,
    min_quality_score                   NUMERIC(5,4)    DEFAULT 0.3000,

    -- 其他扩展字段
    title_boost                         NUMERIC(5,2)    DEFAULT 1.50,
    noise_words                         TEXT,
    meta_extract_rules                  TEXT,
    updated_time                        TIMESTAMP       DEFAULT CURRENT_TIMESTAMP
);

-- 表注释
COMMENT ON TABLE sys_ai_tuning_config IS 'AI系统检索管道核心热调优参数表（含全量质量阈值）';
COMMENT ON COLUMN sys_ai_tuning_config.colbert_veto_threshold
    IS 'ColBERT 单文档否决阈值（0.18）。低于此值被 Veto；>70% 被 Veto 且无赢家触发返回空。';
COMMENT ON COLUMN sys_ai_tuning_config.out_of_corpus_threshold
    IS 'Out-of-Corpus 截断（Reranker 运行时，0.05）。topScore 低于此值认为超出语料库范围。';
COMMENT ON COLUMN sys_ai_tuning_config.out_of_corpus_threshold_no_rerank
    IS 'Out-of-Corpus 截断（Reranker 未运行，0.005）。无精排时置信低，阈值极小防误截断。';
COMMENT ON COLUMN sys_ai_tuning_config.circuit_breaker_enabled
    IS '全局熔断：true=跳过所有 AI 微服务降级纯 BM25，仅 AI 服务完全不可用时临时启用。';

-- 初始化唯一记录（幂等，重复执行安全）
INSERT INTO sys_ai_tuning_config (id)
SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM sys_ai_tuning_config WHERE id = 1);

-- 噪词表初始化（政务实质性词已移除，避免破坏短查询锚词提取）
UPDATE sys_ai_tuning_config
SET noise_words = '什么,怎么,哪些,哪个,如何,为何,为什么,我,你,他,她,它,我们,你们,他们,它们,的,地,得,了,和,与,或,或者,而,且,若,如,并,及,已经,将要,正在,将,都,还,就,才,要,能,可能,也,没有,不,非常,十分,相当,比较,最,更,越,既,尽管,虽然,同样,另外,此外,同时,就是,即,事实上,其实,总之,综上所述,一般而言,对于,关于,根据,按照,依据,应当,应,必须,需要,等'
WHERE id = 1 AND (noise_words IS NULL OR noise_words = '');
