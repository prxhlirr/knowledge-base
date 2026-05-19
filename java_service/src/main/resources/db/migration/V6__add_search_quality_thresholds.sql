-- -- ============================================================
-- -- Flyway V6: 补全检索质量门控阈值字段
-- -- 背景：以下字段在 V1-V5 迁移中均未添加，导致离线全新部署时
-- --       Java getter 回落至硬编码默认值（colbert_veto_threshold=0.35），
-- --       对 ≤4字短词查询触发 CollectiveVeto 误杀，导致「搜不到内容」。
-- -- 修复：以离线友好的宽松默认值初始化这些字段，确保开箱即用。
-- -- ============================================================

-- -- ColBERT 集体否决（CollectiveVeto）阈值
-- -- 原 Java 默认值 0.35 对短词（≤4字）过于严苛，BGE-M3 ColBERT MaxSim
-- -- 在纯词汇匹配场景天然评分偏低（0.15~0.25 属正常），改为 0.18 避免误杀。
-- ALTER TABLE public.sys_ai_tuning_config
--     ADD COLUMN colbert_veto_threshold NUMERIC(5,4) DEFAULT 0.1800;

-- -- Reranker 运行后的 Out-of-Corpus 截断阈值
-- -- 原默认 0.12，对于短词 BM25 normalized_es_score 偏低场景容易误截断，改为 0.05。
-- ALTER TABLE public.sys_ai_tuning_config
--     ADD COLUMN out_of_corpus_threshold NUMERIC(5,4) DEFAULT 0.0500;

-- -- Reranker 未运行（如纯关键词模式）时的 Out-of-Corpus 截断阈值
-- -- 原默认 0.08，对离线/无 GPU 场景几乎必然误截断，改为 0.005（近乎关闭）。
-- ALTER TABLE public.sys_ai_tuning_config
--     ADD COLUMN out_of_corpus_threshold_no_rerank NUMERIC(5,4) DEFAULT 0.0050;

-- -- ES 分数归一化基准线（normalized_es_score = raw / (raw + base)）
-- -- 默认 20.0，hybrid 模式固定用 150.0（代码中已处理），无需修改。
-- ALTER TABLE public.sys_ai_tuning_config
--     ADD COLUMN es_norm_base NUMERIC(10,2) DEFAULT 20.00;

-- -- 每次检索送往 AI 精排的最大文档数量
-- ALTER TABLE public.sys_ai_tuning_config
--     ADD COLUMN rerank_limit INT DEFAULT 15;

-- -- 送往精排的单篇文档文本截断长度
-- ALTER TABLE public.sys_ai_tuning_config
--     ADD COLUMN rerank_max_chars INT DEFAULT 500;

-- -- 字段注释
-- COMMENT ON COLUMN public.sys_ai_tuning_config.colbert_veto_threshold
--     IS 'ColBERT 单文档否决阈值（默认 0.18）。低于此值的文档被标记为 Veto，超过 70% 被 Veto 时返回空。短词查询建议调低至 0.10~0.18。';
-- COMMENT ON COLUMN public.sys_ai_tuning_config.out_of_corpus_threshold
--     IS 'Out-of-Corpus 语料外截断阈值（Reranker 已运行时，默认 0.05）。topScore 低于此值时认为超出知识库范围。';
-- COMMENT ON COLUMN public.sys_ai_tuning_config.out_of_corpus_threshold_no_rerank
--     IS 'Out-of-Corpus 截断阈值（Reranker 未运行时，默认 0.005）。无精排时置信度低，阈值应极小以避免误截断。';
-- COMMENT ON COLUMN public.sys_ai_tuning_config.es_norm_base
--     IS 'ES 原始分数归一化基准线（默认 20.0）。normalized = raw / (raw + base)。';
-- COMMENT ON COLUMN public.sys_ai_tuning_config.rerank_limit
--     IS '每次检索送往 AI 精排的最大文档数量（默认 15，CPU 模式强制上限 12）。';
-- COMMENT ON COLUMN public.sys_ai_tuning_config.rerank_max_chars
--     IS '送往精排的单篇文档文本截断长度（默认 500 字符）。';

-- -- 对已存在的 id=1 记录写入安全默认值（若字段已有非 NULL 值则保留）
-- UPDATE public.sys_ai_tuning_config
-- SET
--     colbert_veto_threshold            = COALESCE(colbert_veto_threshold,            0.1800),
--     out_of_corpus_threshold           = COALESCE(out_of_corpus_threshold,           0.0500),
--     out_of_corpus_threshold_no_rerank = COALESCE(out_of_corpus_threshold_no_rerank, 0.0050),
--     es_norm_base                      = COALESCE(es_norm_base,                      20.00),
--     rerank_limit                      = COALESCE(rerank_limit,                      15),
--     rerank_max_chars                  = COALESCE(rerank_max_chars,                  500)
-- WHERE id = 1;
