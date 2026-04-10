package com.boyang.search.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.boyang.search.entity.SysAiTuningConfig;
import com.boyang.search.mapper.SysAiTuningConfigMapper;
import com.boyang.search.service.SysAiTuningConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class SysAiTuningConfigServiceImpl extends ServiceImpl<SysAiTuningConfigMapper, SysAiTuningConfig> implements SysAiTuningConfigService {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(SysAiTuningConfigServiceImpl.class);

    @Value("${ai.service.host:http://127.0.0.1:8001}")
    private String aiServiceUrl;

    @Value("${ai.models.base-path:/app/models/onnx_native}")
    private String modelBasePath;

    // 为了简单，直接内联创建 RestTemplate。在大型项目中推荐注入 bean
    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void initDatabase() {
        try {
            jdbcTemplate.execute("ALTER TABLE sys_ai_tuning_config ADD COLUMN IF NOT EXISTS high_score_exemption_threshold NUMERIC(10,2) DEFAULT 12.0;");
            jdbcTemplate.execute("ALTER TABLE sys_ai_tuning_config ADD COLUMN IF NOT EXISTS meta_extract_rules TEXT;");
            
            // 语义重构新增参数
            jdbcTemplate.execute("ALTER TABLE sys_ai_tuning_config ADD COLUMN IF NOT EXISTS max_chunk_size INT DEFAULT 500;");
            jdbcTemplate.execute("ALTER TABLE sys_ai_tuning_config ADD COLUMN IF NOT EXISTS min_chunk_size INT DEFAULT 100;");
            jdbcTemplate.execute("ALTER TABLE sys_ai_tuning_config ADD COLUMN IF NOT EXISTS target_chunk_size INT DEFAULT 350;");
            jdbcTemplate.execute("ALTER TABLE sys_ai_tuning_config ADD COLUMN IF NOT EXISTS overlap_size INT DEFAULT 50;");
            jdbcTemplate.execute("ALTER TABLE sys_ai_tuning_config ADD COLUMN IF NOT EXISTS sliding_window_size INT DEFAULT 400;");
            jdbcTemplate.execute("ALTER TABLE sys_ai_tuning_config ADD COLUMN IF NOT EXISTS sliding_window_step INT DEFAULT 350;");
            jdbcTemplate.execute("ALTER TABLE sys_ai_tuning_config ADD COLUMN IF NOT EXISTS min_quality_score NUMERIC(5,2) DEFAULT 0.30;");
            
            logger.info("✅ 数据库自愈探测：成功确认或注入高级语义分片与元信息提取核心字段。");
        } catch (Exception e) {
            logger.warn("⚠️ 数据库自愈探测部分异常：可能无建表权限或该表为初创期。详情: {}", e.getMessage());
        }
    }

    @Override
    public SysAiTuningConfig getGlobalConfig() {
        SysAiTuningConfig config = this.getById(1);
        
        String defaultMetaRules = "[\n" +
            "  {\"key\":\"index_number\", \"label\":\"索引号\", \"regex\":\"索\\\\s*引\\\\s*号\\\\s*[:：]\\\\s*([A-Za-z0-9/\\\\-]+)\"},\n" +
            "  {\"key\":\"theme_classification\", \"label\":\"主题分类\", \"regex\":\"主\\\\s*题\\\\s*分\\\\s*类\\\\s*[:：]\\\\s*([^\\\\n\\\\r]+)\"},\n" +
            "  {\"key\":\"issuing_agency\", \"label\":\"发文机关\", \"regex\":\"发\\\\s*文\\\\s*机\\\\s*关\\\\s*[:：]\\\\s*([^\\\\n\\\\r]+)\"},\n" +
            "  {\"key\":\"written_date\", \"label\":\"成文日期\", \"regex\":\"成\\\\s*文\\\\s*日\\\\s*期\\\\s*[:：]\\\\s*([^\\\\n\\\\r]+)\"},\n" +
            "  {\"key\":\"official_title\", \"label\":\"标题\", \"regex\":\"标\\\\s*题\\\\s*[:：]\\\\s*([^\\\\n\\\\r]+)\"},\n" +
            "  {\"key\":\"document_number\", \"label\":\"发文字号\", \"regex\":\"发\\\\s*文\\\\s*字\\\\s*号\\\\s*[:：]\\\\s*([^\\\\n\\\\r]+)\"},\n" +
            "  {\"key\":\"publish_time\", \"label\":\"发布日期\", \"regex\":\"发\\\\s*布\\\\s*日\\\\s*期\\\\s*[:：]\\\\s*([^\\\\n\\\\r]+)\"}\n" +
            "]";

        if (config == null) {
            // 初始化一条默认配置
            config = new SysAiTuningConfig();
            config.setId(1);
            config.setModelPath(modelBasePath != null ? modelBasePath + "/bge-m3" : "/app/models/onnx_native/bge-m3");
            config.setRerankerPath(modelBasePath != null ? modelBasePath + "/bge-reranker-v2-m3" : "/app/models/onnx_native/bge-reranker-v2-m3");
            config.setCpuThreads(4);
            config.setUseFp16(true);
            config.setBm25Weight(new BigDecimal("0.30"));
            config.setVectorWeight(new BigDecimal("0.70"));
            config.setRrfWindowSize(60);
            config.setCircuitBreakerEnabled(false);
            
            // 补充隐藏参数默认值
            config.setTitleBoost(new BigDecimal("5.0"));
            config.setEsNormBase(new BigDecimal("20.0"));
            config.setRerankLimit(10);
            config.setRerankMaxChars(500);

            // 语义重构的 7 项核心默认值
            config.setMaxChunkSize(500);
            config.setMinChunkSize(100);
            config.setTargetChunkSize(350);
            config.setOverlapSize(50);
            config.setSlidingWindowSize(400);
            config.setSlidingWindowStep(350);
            config.setMinQualityScore(new BigDecimal("0.30"));

            config.setMetaExtractRules(defaultMetaRules);

            config.setUpdatedTime(LocalDateTime.now());
            this.save(config);
        } else if (config.getMetaExtractRules() == null || config.getMetaExtractRules().trim().isEmpty() || config.getMetaExtractRules().trim().equals("[]")) {
            config.setMetaExtractRules(defaultMetaRules);
            config.setUpdatedTime(LocalDateTime.now());
            this.updateById(config);
        }
        return config;
    }

    @Override
    public boolean updateConfigAndNotifyAi(SysAiTuningConfig config) {
        config.setId(1); // 始终保证只改第一条
        config.setUpdatedTime(LocalDateTime.now());
        boolean success = this.updateById(config);
        
        if (success) {
            try {
                // 向 Python 服务下发最新超参热重载命令
                String reloadEndpoint = aiServiceUrl + "/api/ai/config/reload";
                logger.info("Pushing latest tuning config to AI service: {}", reloadEndpoint);
                // POST 请求下发实体配置 (Jackson 自动转 JSON)
                restTemplate.postForObject(reloadEndpoint, config, String.class);
                logger.info("AI service configuration reloaded successfully.");
            } catch (Exception e) {
                logger.error("Failed to notify AI service for config reload. Note: Database is updated but Python context may be stale.", e);
                // 这里可以根据业务决定是否将 DB 保存回滚，一般调优场景建议报错但不回滚，等待连接恢复
            }
        }
        return success;
    }
}
