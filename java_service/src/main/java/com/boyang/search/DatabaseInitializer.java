package com.boyang.search;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

// [重构] 已被 Flyway 迁移脚本（V1__init_schema.sql）替代，禁用此 Bean。
// 保留文件仅供历史参考，请勿删除 @Profile("disabled") 注解。
@Profile("disabled")
@Component
public class DatabaseInitializer implements CommandLineRunner {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) throws Exception {
        System.out.println("====== [DbInit] Starting Database Initializer ======");
        try {
            File sqlFile = new File("../sql/init_db_v3.sql");
            if (sqlFile.exists()) {
                String sql = FileCopyUtils.copyToString(new FileReader(sqlFile));
                String[] statements = sql.split(";");
                for (String stmt : statements) {
                    if (stmt.trim().length() > 0) {
                        try {
                            jdbcTemplate.execute(stmt.trim());
                            System.out.println("✅ Executed: " + stmt.trim().substring(0, Math.min(50, stmt.trim().length())) + "...");
                        } catch (Exception e) {
                            System.err.println("⚠️ Failed to execute statement: " + e.getMessage());
                        }
                    }
                }
                System.out.println("====== [DbInit] Successfully applied init_db_v3.sql ======");
            } else {
                System.out.println("⚠️ [DbInit] Could not find ../sql/init_db_v3.sql");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
