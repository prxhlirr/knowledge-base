package com.boyang.search.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.boyang.search.entity.SysFileParseLog;
import com.boyang.search.mapper.SysFileParseLogMapper;
import com.boyang.search.service.ISysFileParseLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@Configuration
@EnableScheduling
public class SysFileParseLogServiceImpl extends ServiceImpl<SysFileParseLogMapper, SysFileParseLog> implements ISysFileParseLogService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // [修复] 统一使用环境变量，与 Python task_worker 的 INTERNAL_TOKEN 保持一致
    // 原 SYNC_SECRET = "bge_secret_token_123" 与 Python 侧发送的 Token 不匹配，导致 Auth failed
    private static final String SYNC_SECRET = System.getenv("KB_INTERNAL_TOKEN") != null
        ? System.getenv("KB_INTERNAL_TOKEN")
        : "kb-dev-token-change-me-in-prod";

    // @PostConstruct
    // public void initTable() {
    //     // 使用 PostgreSQL 兼容语法建表
    //     String createTableSql = "CREATE TABLE IF NOT EXISTS sys_file_parse_log (" +
    //             "id BIGSERIAL PRIMARY KEY," +
    //             "file_code VARCHAR(128) NOT NULL," +
    //             "file_path VARCHAR(512) NOT NULL," +
    //             "uploader VARCHAR(128)," +
    //             "uploader_dept VARCHAR(256)," +
    //             "upload_time TIMESTAMP," +
    //             "parse_duration_ms INT," +
    //             "parse_result TEXT," +
    //             "chunk_duration_ms INT," +
    //             "chunk_count INT," +
    //             "index_time TIMESTAMP," +
    //             "status SMALLINT NOT NULL DEFAULT 0," +
    //             "is_deleted SMALLINT NOT NULL DEFAULT 0," +
    //             "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
    //             "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
    //             ")";
    //     try {
    //         jdbcTemplate.execute(createTableSql);
    //         // PostgreSQL 索引需要单独创建
    //         jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_fpl_file_code ON sys_file_parse_log(file_code)");
    //         jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_fpl_upload_time ON sys_file_parse_log(upload_time)");
    //         System.out.println("[Database] sys_file_parse_log init OK (PostgreSQL)");
    //     } catch (Exception e) {
    //         System.err.println("[Database] sys_file_parse_log init WARN: " + e.getMessage());
    //     }
    // }

    @Override
    public void handlePythonCallback(Map<String, Object> req) {
        String token = (String) req.get("token");
        if (!SYNC_SECRET.equals(token)) {
            throw new RuntimeException("Auth failed");
        }
        
        String fileCode = (String) req.get("fileCode");
        SysFileParseLog log = this.getOne(new LambdaQueryWrapper<SysFileParseLog>().eq(SysFileParseLog::getFileCode, fileCode));
        if (log == null) return;
        
        Integer status = (Integer) req.get("status");
        log.setStatus(status);
        if (req.containsKey("parseDurationMs")) {
            log.setParseDurationMs((Integer) req.get("parseDurationMs"));
        }
        if (req.containsKey("chunkDurationMs")) {
            log.setChunkDurationMs((Integer) req.get("chunkDurationMs"));
        }
        if (req.containsKey("chunkCount")) {
            log.setChunkCount((Integer) req.get("chunkCount"));
        }
        if (req.containsKey("parseResult")) {
            log.setParseResult((String) req.get("parseResult"));
        }
        if (status == 1) {
            log.setIndexTime(new Date());
        }
        
        this.updateById(log);
    }

    @Override
    public void softRetryTask(String fileCode) {
        SysFileParseLog log = this.getOne(new LambdaQueryWrapper<SysFileParseLog>().eq(SysFileParseLog::getFileCode, fileCode));
        if (log == null) throw new RuntimeException("Log not found");
        
        log.setStatus(0);
        log.setUploadTime(new Date());
        log.setParseResult("");
        this.updateById(log);
        
        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("taskId", fileCode); 
            payloadMap.put("fileCode", fileCode); 
            payloadMap.put("filePath", log.getFilePath());
            payloadMap.put("originalName", new File(log.getFilePath()).getName());
            
            ObjectMapper mapper = new ObjectMapper();
            String taskPayload = mapper.writeValueAsString(payloadMap);
            stringRedisTemplate.opsForList().rightPush("DOC_TASK_QUEUE", taskPayload);
        } catch (Exception e) {
            throw new RuntimeException("Retry dispatch failed: " + e.getMessage());
        }
    }

    // 超时任务守护进程: 10分钟扫描一次卡在 status=0 超出2小时的记录
    @Scheduled(fixedRate = 600000)
    public void cleanupZombieTasks() {
        // 使用 PostgreSQL 兼容的时间函数 (NOW() - INTERVAL '2 hours')
        String updateSql = "UPDATE sys_file_parse_log SET status = 4, " +
                           "parse_result = 'TIMEOUT: task stuck >2h, auto-marked' " +
                           "WHERE status = 0 AND upload_time < NOW() - INTERVAL '2 hours'";
        int updated = jdbcTemplate.update(updateSql);
        if (updated > 0) {
            System.out.println("[Zombie Cleaner] Cleaned " + updated + " zombie tasks");
        }
    }
}
