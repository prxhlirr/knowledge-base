-- ============================================================
-- 知识库系统 · PostgreSQL 全量初始化脚本 v4
-- 适用版本: PostgreSQL 14+
-- 说明: 此脚本为项目所有表的权威 DDL，字段与 Java 实体类完全对齐。
--       所有 ALTER 均使用 IF NOT EXISTS，可在已有库上幂等执行。
-- ============================================================

-- ============================================================
-- 1. sys_tenant_policy —— 租户/应用接入策略表
-- 对应实体: SysTenantPolicy
-- ============================================================
CREATE TABLE IF NOT EXISTS public.sys_tenant_policy (
    id                 BIGSERIAL    PRIMARY KEY,                    -- 主键ID（自增）
    app_code           VARCHAR(64)  NOT NULL UNIQUE,                -- 应用唯一标识（如 ADMIN_MASTER_KEY）
    allowed_indices    VARCHAR(255) NOT NULL,                       -- 允许检索的 ES 索引名前缀（如 kb_*）
    force_file_type    VARCHAR(64),                                 -- 强制过滤的文件类型（null=不限制）
    min_security_level INT          DEFAULT 0,                      -- 最低安全等级（0=公开）
    created_at         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,      -- 策略创建时间
    updated_at         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,      -- 策略最后更新时间
    is_deleted         SMALLINT     DEFAULT 0                       -- 逻辑删除标志（0=正常，1=已删）
);

COMMENT ON TABLE  public.sys_tenant_policy                    IS '租户/应用接入策略表';
COMMENT ON COLUMN public.sys_tenant_policy.id                IS '主键ID';
COMMENT ON COLUMN public.sys_tenant_policy.app_code          IS '应用唯一标识，对应请求头 X-Search-AppCode';
COMMENT ON COLUMN public.sys_tenant_policy.allowed_indices   IS '允许检索的 ES 索引名前缀，支持通配符（如 kb_*）';
COMMENT ON COLUMN public.sys_tenant_policy.force_file_type   IS '强制过滤的文件类型（null 表示不限制）';
COMMENT ON COLUMN public.sys_tenant_policy.min_security_level IS '文档最低安全等级过滤（0=不限）';
COMMENT ON COLUMN public.sys_tenant_policy.created_at        IS '策略创建时间';
COMMENT ON COLUMN public.sys_tenant_policy.updated_at        IS '策略最后更新时间';
COMMENT ON COLUMN public.sys_tenant_policy.is_deleted        IS '逻辑删除标志：0=正常，1=已删除';

-- 初始化默认接入策略
INSERT INTO public.sys_tenant_policy (app_code, allowed_indices, force_file_type, min_security_level)
VALUES
    ('ADMIN_MASTER_KEY', 'kb_*',          NULL,       0),
    ('VEND_A_7788',      'kb_document*',  'document', 0),
    ('VEND_B_9900',      'kb_document*',  NULL,       1),
    ('boyang-kb',        'kb_document_v1', NULL,      0)
ON CONFLICT (app_code) DO UPDATE
    SET allowed_indices    = EXCLUDED.allowed_indices,
        force_file_type    = EXCLUDED.force_file_type,
        min_security_level = EXCLUDED.min_security_level;


-- ============================================================
-- 2. sys_ai_tuning_config —— AI 大模型核心热调优参数表
-- 对应实体: SysAiTuningConfig
-- ============================================================
CREATE TABLE IF NOT EXISTS public.sys_ai_tuning_config (
    id                          SERIAL        PRIMARY KEY,                       -- 主键ID（单行配置，固定为1）
    model_path                  VARCHAR(512)  DEFAULT 'models/bge-m3',           -- Embedding 模型本地绝对路径
    reranker_path               VARCHAR(512)  DEFAULT 'models/bge-reranker-v2-m3', -- Cross-Encoder 重排模型本地绝对路径
    device                      VARCHAR(32)   DEFAULT 'cpu',                     -- 推理设备：cpu 或 cuda
    cpu_threads                 INT           DEFAULT 4,                         -- CPU 推断并行线程数
    use_fp16                    BOOLEAN       DEFAULT TRUE,                      -- 是否启用 FP16 半精度加速
    batch_size                  INT           DEFAULT 16,                        -- 批量向量化并行文档数
    max_length_cpu              INT           DEFAULT 128,                       -- CPU 模式最大 Token 截断长度
    max_length_gpu              INT           DEFAULT 512,                       -- GPU 模式最大 Token 截断长度
    bm25_weight                 NUMERIC(5,2)  DEFAULT 0.30,                      -- BM25 文本得分融合权重
    vector_weight               NUMERIC(5,2)  DEFAULT 0.70,                      -- 向量语义得分融合权重
    rrf_window_size             INT           DEFAULT 60,                        -- RRF 候选召回窗口深度
    circuit_breaker_enabled     BOOLEAN       DEFAULT FALSE,                     -- 全局熔断开关（开启后降级为纯文本检索）
    title_boost                 NUMERIC(8,2)  DEFAULT 5.0,                       -- ES 检索时标题字段增强权重
    es_norm_base                NUMERIC(8,2)  DEFAULT 20.0,                      -- ES 原始分数归一化基准线
    rerank_fusion_ratio         NUMERIC(5,2)  DEFAULT 0.7,                       -- Rerank 分数与 RRF 分数的融合比例
    rerank_limit                INT           DEFAULT 10,                        -- 送往精排的最大文档数
    rerank_max_chars            INT           DEFAULT 500,                       -- 送往精排的单篇文档文本截断字符数
    es_query_timeout            INT           DEFAULT 1500,                      -- ES 检索超时（毫秒）
    embedding_timeout           INT           DEFAULT 1500,                      -- Embedding 接口超时（毫秒）
    rerank_timeout              INT           DEFAULT 1500,                      -- Rerank 接口超时（毫秒）
    lexical_fast_path_max_length INT          DEFAULT 4,                         -- 触发纯词汇快速路径的查询词最大长度
    adaptive_breaker_max_length INT           DEFAULT 4,                         -- 触发短词免重排熔断的最大长度
    breaker_rrf_threshold       NUMERIC(10,4) DEFAULT 0.0200,                    -- 重排熔断的 RRF 分数下限警戒线
    breaker_raw_score_threshold NUMERIC(10,4) DEFAULT 1.0000,                   -- 重排熔断的 ES 原始分数警戒线
    high_score_exemption_threshold NUMERIC(10,4) DEFAULT 12.0,                  -- 高分段豁免重排的 ES 原始分数阈值
    out_of_corpus_threshold     NUMERIC(10,4) DEFAULT 0.12,                      -- 语料外截断阈值（有重排时）
    out_of_corpus_threshold_no_rerank NUMERIC(10,4) DEFAULT 0.08,               -- 语料外截断阈值（无重排时）
    colbert_veto_threshold      NUMERIC(10,4) DEFAULT 0.35,                      -- ColBERT 单文档 Veto 阈值
    max_chunk_size              INT           DEFAULT 500,                       -- 最大语义分块字符数
    min_chunk_size              INT           DEFAULT 100,                       -- 最小向上合并字符数
    target_chunk_size           INT           DEFAULT 350,                       -- 目标语义切分字数
    overlap_size                INT           DEFAULT 50,                        -- 相邻分片重叠字符数
    sliding_window_size         INT           DEFAULT 400,                       -- 兜底滑动窗口大小
    sliding_window_step         INT           DEFAULT 350,                       -- 兜底滑动步长
    min_quality_score           NUMERIC(5,2)  DEFAULT 0.30,                      -- 允许入库的最低切片质量分（0~1）
    knn_num_candidates          INT           DEFAULT 500,                       -- KNN 召回时 HNSW 图候选遍历数
    truthful_ui_max_score_limit NUMERIC(10,4) DEFAULT 0.1500,                   -- [历史字段] 触发界面分数惩罚降维的最高分低门槛判定
    truthful_ui_ceiling         NUMERIC(10,4) DEFAULT 0.7500,                   -- [历史字段] 惩罚后 UI 面板映射的最高分天花板
    meta_extract_rules          TEXT,                                            -- 动态公文元数据提取规则引擎（JSON 数组：[{key,label,regex}]）
    updated_time                TIMESTAMP     DEFAULT CURRENT_TIMESTAMP          -- 配置最后持久化更新时间
);

COMMENT ON TABLE  public.sys_ai_tuning_config                              IS 'AI 大模型核心热调优参数表（单行配置，id 固定为 1）';
COMMENT ON COLUMN public.sys_ai_tuning_config.id                          IS '主键ID，固定值为 1';
COMMENT ON COLUMN public.sys_ai_tuning_config.model_path                  IS 'Embedding 模型在 AI 节点的本地绝对路径';
COMMENT ON COLUMN public.sys_ai_tuning_config.reranker_path               IS 'Cross-Encoder 重排模型在 AI 节点的本地绝对路径';
COMMENT ON COLUMN public.sys_ai_tuning_config.device                      IS '推理设备标识：cpu 或 cuda';
COMMENT ON COLUMN public.sys_ai_tuning_config.cpu_threads                 IS 'CPU 推断模式下的并行线程数限制';
COMMENT ON COLUMN public.sys_ai_tuning_config.use_fp16                    IS '是否启用 FP16 半精度加速（建议仅 GPU 模式开启）';
COMMENT ON COLUMN public.sys_ai_tuning_config.batch_size                  IS '批量向量化时的并行文档条数限制';
COMMENT ON COLUMN public.sys_ai_tuning_config.max_length_cpu              IS 'CPU 模式下文本最大 Token 截断长度';
COMMENT ON COLUMN public.sys_ai_tuning_config.max_length_gpu              IS 'GPU 模式下文本最大 Token 截断长度';
COMMENT ON COLUMN public.sys_ai_tuning_config.bm25_weight                 IS '混合检索中 BM25 文本得分融合权重（0~1）';
COMMENT ON COLUMN public.sys_ai_tuning_config.vector_weight               IS '混合检索中向量语义得分融合权重（0~1）';
COMMENT ON COLUMN public.sys_ai_tuning_config.rrf_window_size             IS 'RRF 融合排序算法候选窗口深度';
COMMENT ON COLUMN public.sys_ai_tuning_config.circuit_breaker_enabled     IS '全局检索熔断开关：TRUE 则跳过 AI 推断降级为纯文本检索';
COMMENT ON COLUMN public.sys_ai_tuning_config.title_boost                 IS 'ES 检索时标题/来源字段增强权重';
COMMENT ON COLUMN public.sys_ai_tuning_config.es_norm_base                IS 'ES 原始分数归一化基准线';
COMMENT ON COLUMN public.sys_ai_tuning_config.rerank_fusion_ratio         IS 'Reranker 分数与 RRF 分数的融合比例';
COMMENT ON COLUMN public.sys_ai_tuning_config.rerank_limit                IS '每次检索送往精排的最大文档数';
COMMENT ON COLUMN public.sys_ai_tuning_config.rerank_max_chars            IS '送往精排时单篇文档文本截断字符数';
COMMENT ON COLUMN public.sys_ai_tuning_config.es_query_timeout            IS 'ES 文本检索超时阈值（毫秒）';
COMMENT ON COLUMN public.sys_ai_tuning_config.embedding_timeout           IS 'Embedding 向量化接口超时（毫秒）';
COMMENT ON COLUMN public.sys_ai_tuning_config.rerank_timeout              IS 'Cross-Encoder 重排接口超时（毫秒）';
COMMENT ON COLUMN public.sys_ai_tuning_config.lexical_fast_path_max_length IS '触发纯词汇快速路径（免 AI 运算）的查询词最大字符长度';
COMMENT ON COLUMN public.sys_ai_tuning_config.adaptive_breaker_max_length IS '触发短词免重排熔断的查询词最大字符长度';
COMMENT ON COLUMN public.sys_ai_tuning_config.breaker_rrf_threshold       IS '触发重排跳过熔断的 RRF 融合分数下限';
COMMENT ON COLUMN public.sys_ai_tuning_config.breaker_raw_score_threshold IS '触发重排跳过熔断的 ES 原始召回分数下限';
COMMENT ON COLUMN public.sys_ai_tuning_config.high_score_exemption_threshold IS '高分段豁免重排的 ES 原始得分阈值';
COMMENT ON COLUMN public.sys_ai_tuning_config.out_of_corpus_threshold     IS 'Reranker 已运行时的语料外截断阈值（最高 rerank 分低于此值则返回空）';
COMMENT ON COLUMN public.sys_ai_tuning_config.out_of_corpus_threshold_no_rerank IS 'Reranker 未运行时的语料外截断阈值（更宽松）';
COMMENT ON COLUMN public.sys_ai_tuning_config.colbert_veto_threshold      IS 'ColBERT MaxSim 单文档 Veto 阈值（低于此值视为无关）';
COMMENT ON COLUMN public.sys_ai_tuning_config.max_chunk_size              IS '语义分块最大字符数';
COMMENT ON COLUMN public.sys_ai_tuning_config.min_chunk_size              IS '语义分块最小字符数（低于此值触发向上合并）';
COMMENT ON COLUMN public.sys_ai_tuning_config.target_chunk_size           IS '语义分块目标字数';
COMMENT ON COLUMN public.sys_ai_tuning_config.overlap_size                IS '相邻分片重叠字符数（保持上下文连贯性）';
COMMENT ON COLUMN public.sys_ai_tuning_config.sliding_window_size         IS '兜底滑动窗口大小（字符数）';
COMMENT ON COLUMN public.sys_ai_tuning_config.sliding_window_step         IS '兜底滑动窗口步长（字符数）';
COMMENT ON COLUMN public.sys_ai_tuning_config.min_quality_score           IS '允许入库的最低切片质量分（0.0~1.0）';
COMMENT ON COLUMN public.sys_ai_tuning_config.knn_num_candidates          IS 'KNN 向量召回时 HNSW 图候选遍历数（值越大召回率越高，但越慢）';
COMMENT ON COLUMN public.sys_ai_tuning_config.meta_extract_rules          IS '动态公文元数据提取规则（JSON 数组格式：[{key,label,regex}]）';
COMMENT ON COLUMN public.sys_ai_tuning_config.updated_time                IS '配置最后一次持久化更新的时间戳';

-- 初始化默认配置行（id=1，仅在不存在时插入）
INSERT INTO public.sys_ai_tuning_config (id) VALUES (1)
ON CONFLICT (id) DO NOTHING;


-- ============================================================
-- 3. search_audit_log —— 搜索审计日志表
-- 对应实体: SearchAuditLog
-- ============================================================
CREATE TABLE IF NOT EXISTS public.search_audit_log (
    id                 BIGSERIAL    PRIMARY KEY,                    -- 主键ID
    app_code           VARCHAR(64)  NOT NULL,                       -- 应用标识
    query_text         TEXT         NOT NULL,                       -- 用户原始输入
    normalized_query   TEXT,                                        -- 标准化/改写后的查询词
    top_hits_count     INT          DEFAULT 0,                      -- 本次检索召回条数
    embedding_cost_ms  INT          DEFAULT 0,                      -- 向量化耗时（毫秒）
    es_cost_ms         INT          DEFAULT 0,                      -- ES 检索耗时（毫秒）
    rerank_cost_ms     INT          DEFAULT 0,                      -- 重排耗时（毫秒）
    total_cost_ms      INT          DEFAULT 0,                      -- 全链路总耗时（毫秒）
    user_id            VARCHAR(64),                                 -- 发起搜索的用户 ID
    create_time        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP       -- 日志记录时间
);

COMMENT ON TABLE  public.search_audit_log                     IS '搜索审计日志表（记录每次检索请求的性能指标及上下文）';
COMMENT ON COLUMN public.search_audit_log.id                 IS '主键ID';
COMMENT ON COLUMN public.search_audit_log.app_code           IS '应用标识（对应 X-Search-AppCode 请求头）';
COMMENT ON COLUMN public.search_audit_log.query_text         IS '用户原始输入查询词';
COMMENT ON COLUMN public.search_audit_log.normalized_query   IS '经 HyDE 或其他方式标准化/改写后的查询词';
COMMENT ON COLUMN public.search_audit_log.top_hits_count     IS '本次检索最终返回的结果条数';
COMMENT ON COLUMN public.search_audit_log.embedding_cost_ms  IS 'Embedding 向量化耗时（毫秒）';
COMMENT ON COLUMN public.search_audit_log.es_cost_ms         IS 'Elasticsearch 混合检索耗时（毫秒）';
COMMENT ON COLUMN public.search_audit_log.rerank_cost_ms     IS 'Cross-Encoder 重排耗时（毫秒）';
COMMENT ON COLUMN public.search_audit_log.total_cost_ms      IS '全链路总耗时（毫秒）';
COMMENT ON COLUMN public.search_audit_log.user_id            IS '发起搜索的用户 ID（来自 JWT 或 Header）';
COMMENT ON COLUMN public.search_audit_log.create_time        IS '日志记录时间';

CREATE INDEX IF NOT EXISTS idx_search_audit_app_time ON public.search_audit_log (app_code, create_time);


-- ============================================================
-- 4. sys_search_tag —— 搜索结果人工打标表
-- 对应实体: SysSearchTag
-- ============================================================
CREATE TABLE IF NOT EXISTS public.sys_search_tag (
    id          BIGSERIAL    PRIMARY KEY,                           -- 主键ID
    doc_id      VARCHAR(128) NOT NULL,                             -- ES 中文档全局唯一标识（_id）
    index_name  VARCHAR(128),                                      -- 文档所在的 ES 索引名
    tags        TEXT,                                              -- 人工补充的标签（逗号分隔）
    keywords    TEXT,                                              -- 人工补充的扩充关键词或自然语言片段
    sync_status SMALLINT     DEFAULT 0,                            -- 同步状态：0=未同步，1=已同步，2=同步失败
    create_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,            -- 打标记录创建时间
    update_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,            -- 打标记录最后修改时间
    CONSTRAINT uq_sys_search_tag_doc_id UNIQUE (doc_id)
);

COMMENT ON TABLE  public.sys_search_tag                       IS '搜索结果人工打标扩展表（用于人工补充标签和关键词并同步到知识库向量网络）';
COMMENT ON COLUMN public.sys_search_tag.id                   IS '主键ID';
COMMENT ON COLUMN public.sys_search_tag.doc_id               IS 'Elasticsearch 文档全局唯一标识（对应 ES _id 字段）';
COMMENT ON COLUMN public.sys_search_tag.index_name           IS '文档所在 ES 索引名称';
COMMENT ON COLUMN public.sys_search_tag.tags                 IS '人工补充的业务标签，逗号分隔（如"核心政策,指导意见"）';
COMMENT ON COLUMN public.sys_search_tag.keywords             IS '人工补充的扩充检索关键词或自然语言片段';
COMMENT ON COLUMN public.sys_search_tag.sync_status          IS '向量同步状态：0=未同步，1=已同步至 ES，2=同步失败';
COMMENT ON COLUMN public.sys_search_tag.create_time          IS '打标记录创建时间';
COMMENT ON COLUMN public.sys_search_tag.update_time          IS '打标记录最后修改时间';

CREATE INDEX IF NOT EXISTS idx_sys_search_tag_doc_id ON public.sys_search_tag (doc_id);


-- ============================================================
-- 5. sys_doc_batch —— 文档批量导入任务批次表
-- 对应实体: SysDocBatch
-- ============================================================
CREATE TABLE IF NOT EXISTS public.sys_doc_batch (
    id            BIGSERIAL    PRIMARY KEY,                        -- 主键ID
    batch_id      VARCHAR(64)  NOT NULL UNIQUE,                    -- 批次唯一标识（UUID）
    source_dir    VARCHAR(512),                                    -- 本地目录接入时的源文件夹路径
    ingest_mode   VARCHAR(32)  DEFAULT 'LOCAL_DIR',                -- 接入模式：UPLOAD / LOCAL_DIR / SFTP / URL
    source_info   TEXT,                                            -- 原始路径或 URL 备忘（JSON 或纯文本）
    total_count   INT          DEFAULT 0,                          -- 批次内文件总数
    success_count INT          DEFAULT 0,                          -- 成功处理文件数
    error_count   INT          DEFAULT 0,                          -- 处理失败文件数
    status        VARCHAR(32)  DEFAULT 'PENDING',                  -- 批次状态：PENDING / IMPORTING / DONE / FAILED
    created_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,          -- 批次创建时间
    updated_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,          -- 批次最后更新时间
    is_deleted    SMALLINT     DEFAULT 0                           -- 逻辑删除标志
);

COMMENT ON TABLE  public.sys_doc_batch                        IS '文档批量导入批次表（一次批量导入操作对应一条批次记录）';
COMMENT ON COLUMN public.sys_doc_batch.id                    IS '主键ID';
COMMENT ON COLUMN public.sys_doc_batch.batch_id              IS '批次唯一标识（UUID）';
COMMENT ON COLUMN public.sys_doc_batch.source_dir            IS '本地目录接入模式时的源文件夹绝对路径';
COMMENT ON COLUMN public.sys_doc_batch.ingest_mode           IS '接入模式：UPLOAD=页面上传，LOCAL_DIR=本地目录，SFTP=远程，URL=链接';
COMMENT ON COLUMN public.sys_doc_batch.source_info           IS '原始接入信息备注（可为 JSON 格式存储路径或 URL 等）';
COMMENT ON COLUMN public.sys_doc_batch.total_count           IS '批次内包含的文件总数';
COMMENT ON COLUMN public.sys_doc_batch.success_count         IS '成功解析并写入 ES 的文件数';
COMMENT ON COLUMN public.sys_doc_batch.error_count           IS '解析或写入失败的文件数';
COMMENT ON COLUMN public.sys_doc_batch.status                IS '批次整体状态：PENDING=待处理，IMPORTING=处理中，DONE=完成，FAILED=失败';
COMMENT ON COLUMN public.sys_doc_batch.created_at            IS '批次创建时间';
COMMENT ON COLUMN public.sys_doc_batch.updated_at            IS '批次状态最后更新时间';
COMMENT ON COLUMN public.sys_doc_batch.is_deleted            IS '逻辑删除标志：0=正常，1=已删除';


-- ============================================================
-- 6. sys_doc_import_task —— 文档导入原子任务表
-- 对应实体: SysDocImportTask
-- ============================================================
CREATE TABLE IF NOT EXISTS public.sys_doc_import_task (
    id            BIGSERIAL     PRIMARY KEY,                       -- 主键ID
    task_id       VARCHAR(64)   NOT NULL UNIQUE,                   -- 任务唯一标识（UUID）
    batch_id      VARCHAR(64)   NOT NULL,                          -- 所属批次 ID（关联 sys_doc_batch.batch_id）
    file_path     VARCHAR(1024) NOT NULL,                          -- 文件物理路径（绝对路径）
    original_name VARCHAR(255),                                    -- 原始文件名（上传时的文件名）
    visibility    VARCHAR(32)   DEFAULT 'INTERNAL',                -- 文档可见度（入库时记录，恢复时使用）
    dept_code     VARCHAR(32),                                     -- 文档所属部门编码（visibility=DEPT 时必填）
    status        VARCHAR(32)   DEFAULT 'IDLE',                    -- 任务状态：IDLE / PENDING / PARSING / INDEXED / ERROR
    error_msg     TEXT,                                            -- 失败时的错误信息
    created_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,         -- 任务创建时间
    updated_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,         -- 任务最后更新时间
    is_deleted    SMALLINT      DEFAULT 0                          -- 逻辑删除标志
);

COMMENT ON TABLE  public.sys_doc_import_task                  IS '文档导入原子任务表（每个文件对应一条任务记录）';
COMMENT ON COLUMN public.sys_doc_import_task.id              IS '主键ID';
COMMENT ON COLUMN public.sys_doc_import_task.task_id         IS '任务唯一标识（UUID）';
COMMENT ON COLUMN public.sys_doc_import_task.batch_id        IS '所属批次标识（关联 sys_doc_batch.batch_id）';
COMMENT ON COLUMN public.sys_doc_import_task.file_path       IS '文件物理存储绝对路径';
COMMENT ON COLUMN public.sys_doc_import_task.original_name   IS '上传时的原始文件名';
COMMENT ON COLUMN public.sys_doc_import_task.visibility      IS '文档可见度（PUBLIC/INTERNAL/DEPT/PRIVATE/GRANT），RecoveryJob 重推时从此字段读取以防权限降级';
COMMENT ON COLUMN public.sys_doc_import_task.dept_code       IS '文档归属部门编码（visibility=DEPT 时必填，12位行政区划码）';
COMMENT ON COLUMN public.sys_doc_import_task.status          IS '任务状态机：IDLE=初始，PENDING=待处理，PARSING=解析中，INDEXED=已入库，ERROR=失败';
COMMENT ON COLUMN public.sys_doc_import_task.error_msg       IS '任务失败时的详细错误信息';
COMMENT ON COLUMN public.sys_doc_import_task.created_at      IS '任务创建时间';
COMMENT ON COLUMN public.sys_doc_import_task.updated_at      IS '任务状态最后更新时间';
COMMENT ON COLUMN public.sys_doc_import_task.is_deleted      IS '逻辑删除标志：0=正常，1=已删除';


-- ============================================================
-- 7. sys_file_parse_log —— 文件解析与向量化全流程日志表
-- 对应实体: SysFileParseLog
-- ============================================================
CREATE TABLE IF NOT EXISTS public.sys_file_parse_log (
    id                BIGSERIAL    PRIMARY KEY,                    -- 主键ID
    file_code         VARCHAR(128) NOT NULL,                       -- 文件唯一 UUID 编码
    file_path         VARCHAR(512) NOT NULL,                       -- 文件物理路径
    uploader          VARCHAR(128),                                -- 上传人账号名或 ID
    uploader_dept     VARCHAR(256),                                -- 上传人所属单位或部门
    upload_time       TIMESTAMP,                                   -- 文件上传时间
    parse_duration_ms INT,                                         -- 文本提取解析耗时（毫秒）
    parse_result      TEXT,                                        -- 解析结果日志（污损/截断等异常详情）
    chunk_duration_ms INT,                                         -- 语义分块及向量化耗时（毫秒）
    chunk_count       INT,                                         -- 实际产生的高质量分片数
    index_time        TIMESTAMP,                                   -- 成功写入 ES 的时间点
    status            SMALLINT     NOT NULL DEFAULT 0,             -- 处理状态（见下方注释）
    created_at        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,      -- 记录创建时间
    updated_at        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,      -- 记录最后更新时间
    is_deleted        SMALLINT     NOT NULL DEFAULT 0              -- 逻辑删除标志
);

COMMENT ON TABLE  public.sys_file_parse_log                      IS '文件解析与向量化全流程日志表（记录每个文件从上传到入库的完整过程）';
COMMENT ON COLUMN public.sys_file_parse_log.id                  IS '主键ID';
COMMENT ON COLUMN public.sys_file_parse_log.file_code           IS '文件唯一 UUID 编码，与 Redis 任务队列的 taskId 一致';
COMMENT ON COLUMN public.sys_file_parse_log.file_path           IS '文件在服务器上的物理存储绝对路径';
COMMENT ON COLUMN public.sys_file_parse_log.uploader            IS '上传人账号名或用户 ID';
COMMENT ON COLUMN public.sys_file_parse_log.uploader_dept       IS '上传人所属单位或部门名称';
COMMENT ON COLUMN public.sys_file_parse_log.upload_time         IS '文件上传至系统的时间';
COMMENT ON COLUMN public.sys_file_parse_log.parse_duration_ms   IS '文本提取解析阶段耗时（毫秒）';
COMMENT ON COLUMN public.sys_file_parse_log.parse_result        IS '解析结果详情（记录污损、截断等异常信息）';
COMMENT ON COLUMN public.sys_file_parse_log.chunk_duration_ms   IS '语义分块及 Embedding 向量化阶段耗时（毫秒）';
COMMENT ON COLUMN public.sys_file_parse_log.chunk_count         IS '实际产生并成功入库的高质量分片总数';
COMMENT ON COLUMN public.sys_file_parse_log.index_time          IS '文档成功双写 ES 并完成向量化的时间点';
COMMENT ON COLUMN public.sys_file_parse_log.status              IS '处理状态：0=解析入库中，1=入库成功，2=文本提取异常，3=分块向量化异常，4=解析超时中断';
COMMENT ON COLUMN public.sys_file_parse_log.created_at          IS '日志记录创建时间';
COMMENT ON COLUMN public.sys_file_parse_log.updated_at          IS '日志记录最后更新时间';
COMMENT ON COLUMN public.sys_file_parse_log.is_deleted          IS '逻辑删除标志：0=正常，1=已删除';

CREATE INDEX IF NOT EXISTS idx_fpl_file_code   ON public.sys_file_parse_log (file_code);
CREATE INDEX IF NOT EXISTS idx_fpl_upload_time ON public.sys_file_parse_log (upload_time);


-- ============================================================
-- 8. kb_doc_registry —— 知识库文档注册中心
-- 对应实体: KbDocRegistry
-- ============================================================
CREATE TABLE IF NOT EXISTS public.kb_doc_registry (
    id            BIGSERIAL     PRIMARY KEY,                       -- 主键ID（自增）
    doc_id        VARCHAR(256)  NOT NULL,                          -- ES _id（source_name:doc_version 生成）
    source_name   VARCHAR(512)  NOT NULL,                          -- 文档原始文件名（与 ES metadata.source 一致）
    doc_version   INT           NOT NULL DEFAULT 1,                -- 版本号（从 1 开始，每次覆盖式上传递增）
    is_latest     SMALLINT      NOT NULL DEFAULT 1,                -- 是否最新版本：1=是，0=历史版本
    storage_path  VARCHAR(1024),                                   -- 文件物理存储路径（本地绝对路径或 MinIO Key）
    target_index  VARCHAR(128)  NOT NULL DEFAULT 'kb_document_v1', -- ES 目标索引名
    chunk_count   INT           DEFAULT 0,                         -- 成功写入 ES 的 chunk 总数
    content_hash  VARCHAR(64),                                     -- 正文前 2000 字 MD5（用于内容去重）
    doc_number    VARCHAR(128),                                     -- 公文文号（如"国发〔2026〕1号"）
    unit          VARCHAR(256),                                    -- 来源单位名称
    tags          VARCHAR(512),                                    -- 文档标签（逗号分隔，如"公安,年报"）
    publish_time  DATE,                                            -- 发文时间
    visibility    VARCHAR(32)   NOT NULL DEFAULT 'INTERNAL',       -- 可见度：PUBLIC/INTERNAL/DEPT/PRIVATE/GRANT
    dept_code     VARCHAR(32),                                     -- 归属部门编码（visibility=DEPT 时必填）
    uploader_id   VARCHAR(64),                                     -- 上传人用户 ID
    uploader_name VARCHAR(128),                                    -- 上传人姓名（冗余，避免关联查询）
    status        VARCHAR(32)   NOT NULL DEFAULT 'INDEXED',        -- 文档状态：INDEXED/FAILED/DELETED
    created_at    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP, -- 记录创建时间（毫秒精度）
    updated_at    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP, -- 记录最后更新时间（毫秒精度）
    UNIQUE (source_name, doc_version)
);

COMMENT ON TABLE  public.kb_doc_registry                         IS '知识库文档注册中心（已入库文档的唯一权威目录，含每个版本完整元数据）';
COMMENT ON COLUMN public.kb_doc_registry.id                     IS '主键ID';
COMMENT ON COLUMN public.kb_doc_registry.doc_id                 IS 'ES 文档 _id（由 source_name + ":" + doc_version 拼接生成）';
COMMENT ON COLUMN public.kb_doc_registry.source_name            IS '文档原始文件名（与 ES metadata.source 字段保持一致）';
COMMENT ON COLUMN public.kb_doc_registry.doc_version            IS '文档版本号（从 1 开始，每次覆盖上传时递增）';
COMMENT ON COLUMN public.kb_doc_registry.is_latest              IS '是否最新版本：1=是（当前有效版本），0=历史版本';
COMMENT ON COLUMN public.kb_doc_registry.storage_path          IS '文件物理存储路径（本地绝对路径或 MinIO Object Key）';
COMMENT ON COLUMN public.kb_doc_registry.target_index          IS '文档写入的 ES 目标索引名（默认 kb_document_v1）';
COMMENT ON COLUMN public.kb_doc_registry.chunk_count           IS '该版本成功写入 ES 的 chunk（分片）总数';
COMMENT ON COLUMN public.kb_doc_registry.content_hash          IS '正文前 2000 字 MD5 哈希，用于跨版本内容去重校验';
COMMENT ON COLUMN public.kb_doc_registry.doc_number            IS '公文文号（如"国发〔2026〕1号"）';
COMMENT ON COLUMN public.kb_doc_registry.unit                  IS '文档来源单位名称';
COMMENT ON COLUMN public.kb_doc_registry.tags                  IS '文档标签（逗号分隔，供前端筛选）';
COMMENT ON COLUMN public.kb_doc_registry.publish_time          IS '公文发文时间';
COMMENT ON COLUMN public.kb_doc_registry.visibility            IS '可见度枚举：PUBLIC=公开，INTERNAL=内部，DEPT=部门，PRIVATE=私有，GRANT=授权访问';
COMMENT ON COLUMN public.kb_doc_registry.dept_code             IS '归属部门行政区划编码（visibility=DEPT 时必填，12位）';
COMMENT ON COLUMN public.kb_doc_registry.uploader_id           IS '上传人用户 ID（对接鉴权体系的用户标识）';
COMMENT ON COLUMN public.kb_doc_registry.uploader_name         IS '上传人姓名（冗余存储，避免关联用户表）';
COMMENT ON COLUMN public.kb_doc_registry.status                IS '文档状态：INDEXED=已入库，FAILED=写入失败，DELETED=已逻辑删除';
COMMENT ON COLUMN public.kb_doc_registry.created_at            IS '记录创建时间（入库时机，毫秒精度）';
COMMENT ON COLUMN public.kb_doc_registry.updated_at            IS '元数据最后更新时间（毫秒精度）';

CREATE INDEX IF NOT EXISTS idx_kdr_source   ON public.kb_doc_registry (source_name);
CREATE INDEX IF NOT EXISTS idx_kdr_latest   ON public.kb_doc_registry (is_latest, status);
CREATE INDEX IF NOT EXISTS idx_kdr_uploader ON public.kb_doc_registry (uploader_id);
CREATE INDEX IF NOT EXISTS idx_kdr_dept     ON public.kb_doc_registry (dept_code);


-- ============================================================
-- 9. kb_doc_grants —— 文档授权明细表
-- 对应实体: KbDocGrants
-- ============================================================
CREATE TABLE IF NOT EXISTS public.kb_doc_grants (
    id           BIGSERIAL     PRIMARY KEY,                        -- 主键ID
    registry_id  BIGINT        NOT NULL REFERENCES public.kb_doc_registry (id), -- 关联文档注册表主键
    source_name  VARCHAR(512)  NOT NULL,                           -- 文档 source_name（冗余，避免 JOIN）
    grantee_id   VARCHAR(64)   NOT NULL,                           -- 被授权人 ID（对应 X-User-Id Header）
    grantee_name VARCHAR(128),                                     -- 被授权人姓名（冗余，方便展示）
    expires_at   TIMESTAMP,                                        -- 授权到期时间（NULL=永久授权）
    granted_by   VARCHAR(64)   NOT NULL,                           -- 授权人 ID（审计使用）
    remark       VARCHAR(256),                                     -- 授权原因/备注
    is_active    SMALLINT      NOT NULL DEFAULT 1,                 -- 是否有效：1=有效，0=已撤销
    created_at   TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP, -- 授权创建时间
    updated_at   TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP  -- 最后修改时间（撤销时更新）
);

COMMENT ON TABLE  public.kb_doc_grants                           IS '文档授权明细表（GRANT 类文档的被授权关系，以 PostgreSQL 为唯一权威来源）';
COMMENT ON COLUMN public.kb_doc_grants.id                       IS '主键ID';
COMMENT ON COLUMN public.kb_doc_grants.registry_id              IS '关联 kb_doc_registry.id（使用主键关联，比 ES doc_id 更稳定）';
COMMENT ON COLUMN public.kb_doc_grants.source_name              IS '文档 source_name（冗余存储，供 PermissionGuard 高频查询时避免 JOIN）';
COMMENT ON COLUMN public.kb_doc_grants.grantee_id               IS '被授权人用户 ID（与 X-User-Id 请求头一致）';
COMMENT ON COLUMN public.kb_doc_grants.grantee_name             IS '被授权人姓名（冗余，便于管理端展示，无需关联用户表）';
COMMENT ON COLUMN public.kb_doc_grants.expires_at               IS '授权到期时间（NULL 表示永久授权）';
COMMENT ON COLUMN public.kb_doc_grants.granted_by               IS '授权操作人 ID（审计追溯使用）';
COMMENT ON COLUMN public.kb_doc_grants.remark                   IS '授权原因或备注信息（供审计追溯）';
COMMENT ON COLUMN public.kb_doc_grants.is_active                IS '授权状态：1=有效，0=已撤销（软删除，保留审计历史）';
COMMENT ON COLUMN public.kb_doc_grants.created_at               IS '授权记录创建时间';
COMMENT ON COLUMN public.kb_doc_grants.updated_at               IS '授权记录最后修改时间（撤销时更新）';

-- 同一文档同一用户只允许一条有效授权
CREATE UNIQUE INDEX IF NOT EXISTS idx_kdg_unique_grant ON public.kb_doc_grants (source_name, grantee_id) WHERE is_active = 1;
CREATE INDEX IF NOT EXISTS idx_kdg_source  ON public.kb_doc_grants (source_name, is_active);
CREATE INDEX IF NOT EXISTS idx_kdg_grantee ON public.kb_doc_grants (grantee_id, is_active);


-- ============================================================
-- 10. doc_version_history —— 文档版本历史溯源表（不可变）
-- 对应实体: DocVersionHistory
-- ============================================================
CREATE TABLE IF NOT EXISTS public.doc_version_history (
    id           BIGSERIAL     PRIMARY KEY,                        -- 主键ID
    source_name  VARCHAR(512)  NOT NULL,                           -- 文档名称（与 ES metadata.source 一致）
    doc_version  INT           NOT NULL,                           -- 版本号（从 1 开始，与 ES doc_version 一致）
    content_hash VARCHAR(64),                                      -- 正文前 2000 字 MD5（用于内容去重）
    chunk_count  INT,                                              -- 该版本 chunk 总数
    operator_id  VARCHAR(64),                                      -- 操作人 ID（上传者）
    visibility   VARCHAR(32)   DEFAULT 'INTERNAL',                 -- 该版本可见度快照（PUBLIC/INTERNAL/DEPT/PRIVATE/GRANT）
    created_at   TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP, -- 版本创建时间（毫秒精度，不可变）
    UNIQUE (source_name, doc_version)
);

COMMENT ON TABLE  public.doc_version_history                     IS '文档版本历史溯源表（只追加写入，不允许修改，用于版本对比与去重校验）';
COMMENT ON COLUMN public.doc_version_history.id                 IS '主键ID';
COMMENT ON COLUMN public.doc_version_history.source_name        IS '文档名称（与 ES metadata.source 字段保持一致）';
COMMENT ON COLUMN public.doc_version_history.doc_version        IS '文档版本号（从 1 开始，与 ES doc_version 字段一致）';
COMMENT ON COLUMN public.doc_version_history.content_hash       IS '正文前 2000 字 MD5 哈希，用于跨版本内容去重查询';
COMMENT ON COLUMN public.doc_version_history.chunk_count        IS '该版本的 chunk 总数';
COMMENT ON COLUMN public.doc_version_history.operator_id        IS '操作人（上传者）用户 ID';
COMMENT ON COLUMN public.doc_version_history.visibility         IS '该版本入库时的可见度快照';
COMMENT ON COLUMN public.doc_version_history.created_at         IS '版本记录创建时间（入库时机，毫秒精度，只写不改）';

CREATE INDEX IF NOT EXISTS idx_dvh_source ON public.doc_version_history (source_name);
CREATE INDEX IF NOT EXISTS idx_dvh_hash   ON public.doc_version_history (content_hash);


-- ============================================================
-- 11. doc_permission_events —— 权限变更事件溯源表
-- 对应实体: DocPermissionEvent
-- ============================================================
CREATE TABLE IF NOT EXISTS public.doc_permission_events (
    id           BIGSERIAL    PRIMARY KEY,                         -- 主键ID
    doc_id       VARCHAR(512) NOT NULL,                            -- 文档标识（source_name 或 ES _id）
    action       VARCHAR(32)  NOT NULL CHECK (action IN ('GRANT','REVOKE','HANDLER','VISIBILITY_CHANGE')), -- 权限变更动作
    target_type  VARCHAR(16)  NOT NULL CHECK (target_type IN ('USER','DEPT','GROUP')),                    -- 授权目标类型
    target_value VARCHAR(128) NOT NULL,                            -- 授权目标值（用户ID/部门编码/组编码）
    operator_id  VARCHAR(64)  NOT NULL,                            -- 操作人 ID（审计）
    remark       VARCHAR(256),                                     -- 操作备注
    created_at   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP   -- 事件发生时间（毫秒精度）
);

COMMENT ON TABLE  public.doc_permission_events                   IS '文档权限变更事件溯源表（只追加写入，记录所有权限变更历史用于审计）';
COMMENT ON COLUMN public.doc_permission_events.id               IS '主键ID';
COMMENT ON COLUMN public.doc_permission_events.doc_id           IS '文档标识（source_name 或 ES _id）';
COMMENT ON COLUMN public.doc_permission_events.action           IS '权限变更动作：GRANT=授权，REVOKE=撤销，HANDLER=处理，VISIBILITY_CHANGE=可见度变更';
COMMENT ON COLUMN public.doc_permission_events.target_type      IS '授权目标类型：USER=用户，DEPT=部门，GROUP=用户组';
COMMENT ON COLUMN public.doc_permission_events.target_value     IS '授权目标值（用户ID/部门编码/用户组编码）';
COMMENT ON COLUMN public.doc_permission_events.operator_id      IS '执行本次权限变更的操作人 ID';
COMMENT ON COLUMN public.doc_permission_events.remark           IS '操作备注说明';
COMMENT ON COLUMN public.doc_permission_events.created_at       IS '权限变更事件发生时间（毫秒精度，只写不改）';

CREATE INDEX IF NOT EXISTS idx_dpe_doc    ON public.doc_permission_events (doc_id);
CREATE INDEX IF NOT EXISTS idx_dpe_time   ON public.doc_permission_events (created_at);
CREATE INDEX IF NOT EXISTS idx_dpe_target ON public.doc_permission_events (target_type, target_value);


-- ============================================================
-- 12. permission_groups —— 权限用户组定义表
-- ============================================================
CREATE TABLE IF NOT EXISTS public.permission_groups (
    id          BIGSERIAL    PRIMARY KEY,                          -- 主键ID
    group_code  VARCHAR(64)  UNIQUE NOT NULL,                      -- 用户组唯一编码
    group_name  VARCHAR(128) NOT NULL,                             -- 用户组显示名称
    description VARCHAR(256),                                      -- 用户组说明描述
    created_by  VARCHAR(64),                                       -- 创建人 ID
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP             -- 用户组创建时间
);

COMMENT ON TABLE  public.permission_groups                       IS '权限用户组定义表（用于批量授权管理）';
COMMENT ON COLUMN public.permission_groups.id                   IS '主键ID';
COMMENT ON COLUMN public.permission_groups.group_code           IS '用户组唯一编码（业务标识）';
COMMENT ON COLUMN public.permission_groups.group_name           IS '用户组显示名称';
COMMENT ON COLUMN public.permission_groups.description          IS '用户组功能说明描述';
COMMENT ON COLUMN public.permission_groups.created_by           IS '创建该用户组的操作人 ID';
COMMENT ON COLUMN public.permission_groups.created_at           IS '用户组创建时间';


-- ============================================================
-- 13. permission_group_members —— 权限用户组成员表
-- ============================================================
CREATE TABLE IF NOT EXISTS public.permission_group_members (
    id         BIGSERIAL   PRIMARY KEY,                            -- 主键ID
    group_code VARCHAR(64) NOT NULL,                               -- 所属用户组编码（关联 permission_groups.group_code）
    dept_code  VARCHAR(32),                                        -- 成员部门编码（dept 维度成员时填写）
    user_id    VARCHAR(64),                                        -- 成员用户 ID（user 维度成员时填写）
    granted_by VARCHAR(64),                                        -- 添加此成员的操作人 ID
    granted_at TIMESTAMP   DEFAULT CURRENT_TIMESTAMP               -- 成员加入时间
);

COMMENT ON TABLE  public.permission_group_members                IS '权限用户组成员表（记录组内各部门或用户成员）';
COMMENT ON COLUMN public.permission_group_members.id            IS '主键ID';
COMMENT ON COLUMN public.permission_group_members.group_code    IS '所属用户组编码（关联 permission_groups.group_code）';
COMMENT ON COLUMN public.permission_group_members.dept_code     IS '部门维度成员的部门编码（dept_code 与 user_id 二选一填写）';
COMMENT ON COLUMN public.permission_group_members.user_id       IS '用户维度成员的用户 ID（dept_code 与 user_id 二选一填写）';
COMMENT ON COLUMN public.permission_group_members.granted_by    IS '将该成员加入用户组的操作人 ID';
COMMENT ON COLUMN public.permission_group_members.granted_at    IS '成员加入用户组的时间';

CREATE INDEX IF NOT EXISTS idx_pgm_group ON public.permission_group_members (group_code);
CREATE INDEX IF NOT EXISTS idx_pgm_dept  ON public.permission_group_members (dept_code);


-- ============================================================
-- 14. dept_tree —— 部门组织树表
-- ============================================================
CREATE TABLE IF NOT EXISTS public.dept_tree (
    dept_code   VARCHAR(32)  PRIMARY KEY,                          -- 部门唯一编码（行政区划码或自定义编码）
    dept_name   VARCHAR(128) NOT NULL,                             -- 部门名称
    dept_l2     VARCHAR(4),                                        -- 第2级编码（省级，4位）
    dept_l4     VARCHAR(8),                                        -- 第4级编码（市级，8位）
    dept_l6     VARCHAR(12),                                       -- 第6级编码（区县级，12位）
    dept_l9     VARCHAR(18),                                       -- 第9级编码（乡镇/街道级，18位）
    dept_level  SMALLINT,                                          -- 部门所处层级（1~9）
    parent_code VARCHAR(32),                                       -- 父级部门编码
    is_active   SMALLINT     DEFAULT 1                             -- 是否启用：1=启用，0=停用
);

COMMENT ON TABLE  public.dept_tree                               IS '部门组织树表（按行政区划层级存储部门信息，用于权限范围判定）';
COMMENT ON COLUMN public.dept_tree.dept_code                    IS '部门唯一编码（行政区划码或系统自定义编码）';
COMMENT ON COLUMN public.dept_tree.dept_name                    IS '部门名称';
COMMENT ON COLUMN public.dept_tree.dept_l2                      IS '省级（第2级）行政区划编码（4位）';
COMMENT ON COLUMN public.dept_tree.dept_l4                      IS '市级（第4级）行政区划编码（8位）';
COMMENT ON COLUMN public.dept_tree.dept_l6                      IS '区县级（第6级）行政区划编码（12位）';
COMMENT ON COLUMN public.dept_tree.dept_l9                      IS '乡镇街道级（第9级）行政区划编码（18位）';
COMMENT ON COLUMN public.dept_tree.dept_level                   IS '部门所处组织层级（1=根，9=最细粒度）';
COMMENT ON COLUMN public.dept_tree.parent_code                  IS '父级部门编码（顶级部门为 NULL）';
COMMENT ON COLUMN public.dept_tree.is_active                    IS '启用状态：1=启用，0=停用';

-- ============================================================
-- END OF SCRIPT
-- ============================================================
