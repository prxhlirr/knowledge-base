-- ============================================================
-- Flyway V2: sys_ai_tuning_config 扩展字段补全
-- 来源：SearchService.java @PostConstruct initDatabaseColumns()
-- 使用 ADD COLUMN IF NOT EXISTS 保证幂等性（兼容 PostgreSQL 9.6+）
-- ============================================================

ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS es_query_timeout            INT           DEFAULT 1500;
ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS embedding_timeout            INT           DEFAULT 1500;
ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS rerank_timeout               INT           DEFAULT 1500;
ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS lexical_fast_path_max_length INT           DEFAULT 4;
ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS adaptive_breaker_max_length  INT           DEFAULT 4;
ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS breaker_rrf_threshold        NUMERIC(10,4) DEFAULT 0.0200;
ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS breaker_raw_score_threshold  NUMERIC(10,4) DEFAULT 1.0000;
ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS truthful_ui_max_score_limit  NUMERIC(10,4) DEFAULT 0.1500;
ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS truthful_ui_ceiling          NUMERIC(10,4) DEFAULT 0.7500;
ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS quality_breaker_floor        NUMERIC(10,4) DEFAULT 0.0600;

COMMENT ON COLUMN public.sys_ai_tuning_config.es_query_timeout              IS 'ES 检索时 Text 底座容忍的超时间隔(ms)';
COMMENT ON COLUMN public.sys_ai_tuning_config.embedding_timeout             IS '向量模型生成 Embedding 的接口硬超时(ms)';
COMMENT ON COLUMN public.sys_ai_tuning_config.rerank_timeout                IS '深度重排 Cross-Encoder 接口硬超时(ms)';
COMMENT ON COLUMN public.sys_ai_tuning_config.lexical_fast_path_max_length  IS '触发"纯词汇抢跑"免大模型运算的极限长度';
COMMENT ON COLUMN public.sys_ai_tuning_config.adaptive_breaker_max_length   IS '触发"短词免重排"熔断的极限长度';
COMMENT ON COLUMN public.sys_ai_tuning_config.breaker_rrf_threshold         IS '触发重排熔断的 RRF 融合分数下限警戒线';
COMMENT ON COLUMN public.sys_ai_tuning_config.breaker_raw_score_threshold   IS '触发重排熔断的 ES 原始召回分数警戒线';
COMMENT ON COLUMN public.sys_ai_tuning_config.truthful_ui_max_score_limit   IS '触发界面惩罚降维的底层最高分低门槛判定';
COMMENT ON COLUMN public.sys_ai_tuning_config.truthful_ui_ceiling           IS '触发界面惩罚后 UI 面板映射的最高分天花板';
COMMENT ON COLUMN public.sys_ai_tuning_config.quality_breaker_floor         IS '最低可用质量门控阈值，低于此值的结果不展示';
