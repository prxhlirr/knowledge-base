CREATE TABLE IF NOT EXISTS sys_ai_tuning_config (
    id SERIAL PRIMARY KEY,
    model_path VARCHAR(255) DEFAULT 'E:\project\AI\knowledge-base\models\bge-m3',
    reranker_path VARCHAR(255) DEFAULT 'E:\project\AI\knowledge-base\models\bge-reranker-v2-m3',
    device VARCHAR(50) DEFAULT 'cpu',
    cpu_threads INT DEFAULT 4,
    use_fp16 BOOLEAN DEFAULT true,
    batch_size INT DEFAULT 16,
    max_length_cpu INT DEFAULT 128,
    max_length_gpu INT DEFAULT 512,
    bm25_weight NUMERIC(5,2) DEFAULT 0.3,
    vector_weight NUMERIC(5,2) DEFAULT 0.7,
    rrf_window_size INT DEFAULT 60,
    circuit_breaker_enabled BOOLEAN DEFAULT false,
    updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 添加表注释
COMMENT ON TABLE sys_ai_tuning_config IS 'AI系统大模型核心热调优参数表';

-- 添加字段注释
COMMENT ON COLUMN sys_ai_tuning_config.id IS '主键ID';
COMMENT ON COLUMN sys_ai_tuning_config.model_path IS 'Embedding模型服务器端本地加载绝对路径';
COMMENT ON COLUMN sys_ai_tuning_config.reranker_path IS 'Cross-Encoder重排模型服务器端本地加载绝对路径';
COMMENT ON COLUMN sys_ai_tuning_config.device IS '推理设备指定: cpu 或 cuda';
COMMENT ON COLUMN sys_ai_tuning_config.cpu_threads IS 'CPU 推断模式下的并行线程数限制';
COMMENT ON COLUMN sys_ai_tuning_config.use_fp16 IS '是否启用半精度(FP16)以加速推断并降低显存占用';
COMMENT ON COLUMN sys_ai_tuning_config.batch_size IS '批量向量化时的并行文档条数限制';
COMMENT ON COLUMN sys_ai_tuning_config.max_length_cpu IS 'CPU模式下文本允许的最大Token截断长度';
COMMENT ON COLUMN sys_ai_tuning_config.max_length_gpu IS 'GPU模式下文本允许的最大Token截断长度';
COMMENT ON COLUMN sys_ai_tuning_config.bm25_weight IS '混合检索中传统BM25文本得分所占的权重占比 (0-1)';
COMMENT ON COLUMN sys_ai_tuning_config.vector_weight IS '混合检索中向量语义相似度得分所占的权重占比 (0-1)';
COMMENT ON COLUMN sys_ai_tuning_config.rrf_window_size IS 'RRF融合排序算法探测的候选窗口深度';
COMMENT ON COLUMN sys_ai_tuning_config.circuit_breaker_enabled IS '系统全局检索熔断开关：开启后将跳过AI推断直接使用传统检索降级';
COMMENT ON COLUMN sys_ai_tuning_config.updated_time IS '配置最后一次生效变更的时间戳';

-- 初始化数据
INSERT INTO sys_ai_tuning_config (id)
SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM sys_ai_tuning_config WHERE id = 1);

