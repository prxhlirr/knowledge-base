-- ============================================================
-- Flyway V3: 检索管道硬编码治理 —— 将 SearchService 魔法值迁移至 sys_ai_tuning_config
-- 设计决策：
--   1. 每字段单独一条 ALTER（与 V2 风格保持一致，避免多列 ALTER 在 Flyway 解析中产生歧义）
--   2. RRF k 值复用现有 rrf_window_size 字段（方案A），不新增 rrf_k 冗余列
--   3. 噪词表中「要求」「通知」「规定」等实质性政务词已移除（已验证会破坏锚词提取）
-- ============================================================

ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS qa_sim_threshold          NUMERIC(5,4) DEFAULT 0.8200;
ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS colloquial_sim_threshold  NUMERIC(5,4) DEFAULT 0.7800;
ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS knn_min_sim               NUMERIC(5,4) DEFAULT 0.1500;
ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS qa_knn_candidates         INT          DEFAULT 50;
ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS colloquial_knn_candidates INT          DEFAULT 80;
ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS hyde_min_sim              NUMERIC(5,4) DEFAULT 0.7500;
ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS noise_words               TEXT;

COMMENT ON COLUMN public.sys_ai_tuning_config.qa_sim_threshold          IS 'QA 检索通道语义准入阈值（默认 0.82）';
COMMENT ON COLUMN public.sys_ai_tuning_config.colloquial_sim_threshold  IS '口语化检索通道语义准入阈值（默认 0.78）';
COMMENT ON COLUMN public.sys_ai_tuning_config.knn_min_sim               IS 'KNN 命中最低相似度门控（默认 0.15，低于此值丢弃）';
COMMENT ON COLUMN public.sys_ai_tuning_config.qa_knn_candidates         IS 'QA 通道 KNN numCandidates（默认 50）';
COMMENT ON COLUMN public.sys_ai_tuning_config.colloquial_knn_candidates IS '口语化通道 KNN numCandidates（默认 80）';
COMMENT ON COLUMN public.sys_ai_tuning_config.hyde_min_sim              IS 'HyDE 质量门控：假设文档向量与查询向量相似度下限（默认 0.75）';
COMMENT ON COLUMN public.sys_ai_tuning_config.noise_words               IS '政务查询噪词表（逗号分隔）。勿放实质性政务词如「要求」「通知」「规定」';

-- 初始化默认噪词（「要求」已移除，避免破坏「总体要求」等短查询的锚词提取）
UPDATE public.sys_ai_tuning_config
SET noise_words = '什么,怎么,哪些,哪个,如何,为何,为什么,我,你,他,她,它,我们,你们,他们,它们,的,地,得,了,和,与,或,或者,而,且,若,如,并,及,已经,将要,正在,将,都,还,就,才,要,能,可能,也,没有,不,非常,十分,相当,比较,最,更,越,既,尽管,虽然,同样,另外,此外,同时,就是,即,事实上,其实,总之,综上所述,一般而言,对于,关于,根据,按照,依据,应当,应,必须,需要,等'
WHERE id = 1;
