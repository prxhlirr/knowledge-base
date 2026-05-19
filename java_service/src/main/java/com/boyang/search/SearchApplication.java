package com.boyang.search;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import com.alibaba.druid.spring.boot.autoconfigure.DruidDataSourceAutoConfigure;

@EnableAsync
@EnableScheduling
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, DruidDataSourceAutoConfigure.class})
public class SearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
        System.out.println("====== 向量搜索中台后端网关启动成功 (Java 8 / Spring Boot 2.3.5.RELEASE) ======");
    }

    @Bean
    public CommandLineRunner initData(JdbcTemplate jdbc) {
        return args -> {
            System.out.println("🚀 [Init] Checking and initializing test data...");
            // 1. 创建审计日志表
            jdbc.execute("CREATE TABLE IF NOT EXISTS search_audit_log (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "app_code VARCHAR(64) NOT NULL," +
                    "query_text TEXT NOT NULL," +
                    "normalized_query TEXT," +
                    "top_hits_count INT DEFAULT 0," +
                    "embedding_cost_ms INT DEFAULT 0," +
                    "es_cost_ms INT DEFAULT 0," +
                    "rerank_cost_ms INT DEFAULT 0," +
                    "total_cost_ms INT DEFAULT 0," +
                    "user_id VARCHAR(64)," +
                    "create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            
            // 2. 插入测试厂家策略 (boyang-kb)
            // 先删再插保证最新
            jdbc.execute("DELETE FROM sys_tenant_policy WHERE app_code = 'boyang-kb'");
            jdbc.execute("INSERT INTO sys_tenant_policy (app_code, allowed_indices, force_file_type, min_security_level, is_deleted, created_at) " +
                    "VALUES ('boyang-kb', 'knowledge_base', 'PDF', 1, 0, CURRENT_TIMESTAMP)");
            
            System.out.println("✅ [Init] Metadata & Policy 'boyang-kb' initialized.");
        };
    }
}
