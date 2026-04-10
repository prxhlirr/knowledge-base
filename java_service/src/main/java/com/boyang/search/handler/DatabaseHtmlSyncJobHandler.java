package com.boyang.search.handler;

import com.boyang.search.model.DocIngestRequest;
import com.boyang.search.service.DocIngestService;
import com.xxl.job.core.log.XxlJobLogger;
import com.xxl.job.core.handler.annotation.XxlJob;
import com.xxl.job.core.biz.model.ReturnT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 数据库HTML内容抽取同步至知识库处理器。
 * <p>
 * 核心设计原则（修复版）：
 *   - 不再直接操作 Redis 推送任务，而是通过 DocIngestService 走完整的标准入库链路。
 *   - 完整链路保证了 kb_doc_outbox、sys_doc_import_task 等记录的正确写入，
 *     以及后续 OutboxPoller 将 ES 中的 is_latest 从 false 切换到 true。
 * </p>
 * <p>
 * 之前的 Bug 根因：
 *   1. 直接 rightPush Redis，跳过了 DocIngestService，导致 kb_doc_outbox 表从未写入记录。
 *   2. Python 处理完回调 /api/v1/internal/doc/registry 时，Java 按 taskId 查 outbox → 找不到 →
 *      提前 return，不写 kb_doc_registry，也不触发 2PC ES 切换。
 *   3. ES 中 is_latest 在写入时就是 false（这是正确的），但后续没有人将其置 true。
 * </p>
 */
@Component
public class DatabaseHtmlSyncJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseHtmlSyncJobHandler.class);

    /**
     * HTML 临时文件存储目录（容器内路径，与 Python Worker 共享 volume）。
     * 必须与 ai_service 中 Python 能读取到的路径一致，否则 Python 找不到文件。
     */
    @Value("${knowledge-base.extract.html-temp-dir:/data/applogs/kb/storage/temp}")
    private String htmlTempDir;

    // 主数据源的 JdbcTemplate（用于查询源数据 temp_html_parse 表）
    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 注入 DocIngestService，通过标准入库链路处理，保证 outbox 记录正确写入。
     * 这是修复 is_latest=false 和 kb_doc_outbox 无记录的核心关键。
     */
    @Autowired
    private DocIngestService docIngestService;

    /**
     * XXL-JOB 调度入口：dbHtmlExtractJob
     * <p>
     * 业务功能：
     *   1. 批量提取 temp_html_parse 表中状态为未处理的数据。
     *   2. 将 HTML 内容写入临时 .html 文件到共享存储目录。
     *   3. 通过 DocIngestService（LOCAL 模式）走完整的标准入库链路，
     *      保证 kb_doc_outbox、sys_doc_import_task 等正确写入，
     *      使 OutboxPoller 能将 ES 中 is_latest 从 false 切换到 true。
     *   4. 成功入库后标记源记录状态为已处理。
     * </p>
     */
    @XxlJob("dbHtmlExtractJob")
    public ReturnT<String> execute(String param) throws Exception {
        XxlJobLogger.log("启动 DatabaseHtmlSyncJob，HTML 临时目录: " + htmlTempDir);

        // 确保临时目录存在
        File tempDirFile = new File(htmlTempDir);
        if (!tempDirFile.exists()) {
            boolean created = tempDirFile.mkdirs();
            if (!created) {
                XxlJobLogger.log("❌ 临时目录创建失败: " + htmlTempDir);
                return ReturnT.FAIL;
            }
        }

        // TODO: 替换为实际业务的查询语句
        String querySql = "SELECT * FROM temp_html_parse WHERE status = '0' LIMIT 100";
        String updateSql = "UPDATE temp_html_parse SET status = '1' WHERE id = ?";

        List<Map<String, Object>> records;
        try {
            records = jdbcTemplate.queryForList(querySql);
        } catch (Exception e) {
            XxlJobLogger.log("查询源数据库失败，请检查源表名: " + e.getMessage());
            return ReturnT.FAIL;
        }

        if (records == null || records.isEmpty()) {
            XxlJobLogger.log("本次无可处理的新增数据");
            return ReturnT.SUCCESS;
        }

        int successCount = 0;
        int failCount = 0;

        for (Map<String, Object> record : records) {
            String id = String.valueOf(record.get("id"));
            String title = String.valueOf(record.getOrDefault("title", "未命名从库文档_" + id));
            String htmlContent = String.valueOf(record.getOrDefault("content", ""));
            // [Fix] 根据数据库实际列名映射字段
            String author = String.valueOf(record.getOrDefault("user_name", "DB_BOT"));
            String pubTime = record.get("publish_time") != null ? String.valueOf(record.get("publish_time")) : null;
            String deptCode = record.get("org_code") != null ? String.valueOf(record.get("org_code")) : null;
            String unit = record.get("org_name") != null ? String.valueOf(record.get("org_name")) : null;

            if (htmlContent.trim().isEmpty()) {
                XxlJobLogger.log("记录 [{0}] HTML 内容为空，跳过", id);
                continue;
            }

            // 确保文件名合法（去除特殊字符，防止文件系统报错）
            String safeTitle = title.replaceAll("[\\\\/:*?\"<>|]", "_");
            String fileName = safeTitle + ".html";
            File tempFile = new File(tempDirFile, "db_" + id + "_" + System.currentTimeMillis() + ".html");

            try {
                // 1. 将 HTML 内容写入临时文件（Python 会通过本地路径读取）
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    fos.write(htmlContent.getBytes(StandardCharsets.UTF_8));
                }
                XxlJobLogger.log("记录 [{0}] HTML 已写入临时文件: {1}", id, tempFile.getAbsolutePath());

                // 2. 构造标准入库请求，走 DocIngestService 完整链路
                // LOCAL 模式：Python Worker 通过 filePath 直接读取本地文件
                // 关键：DocIngestService.createAndDispatch 会写 kb_doc_outbox，
                //        保证 OutboxPoller 能正确将 is_latest 切换为 true
                DocIngestRequest req = new DocIngestRequest();
                req.setIngestType("LOCAL");
                req.setFilePath(tempFile.getAbsolutePath());
                req.setFileName(fileName);
                req.setVisibility("INTERNAL");
                req.setTargetIndex("kb_document_v1");
                req.setOwner(author);
                req.setDeptCode(deptCode);
                req.setUnit(unit);
                req.setSourceSystem("DB_HTML_SYNC");

                if (pubTime != null && !pubTime.trim().isEmpty() && !"null".equalsIgnoreCase(pubTime.trim())) {
                    // 截取 yyyy-MM-dd 格式（兼容 datetime 类型）
                    req.setPublishTime(pubTime.length() >= 10 ? pubTime.substring(0, 10) : pubTime);
                }

                // 3. 通过 DocIngestService 完整入库（自动写 outbox + 推 Redis）
                String batchId = docIngestService.ingest(req);
                XxlJobLogger.log("记录 [{0}] 入库成功，batchId={1}", id, batchId);

                // 4. 标记源记录为已处理
                jdbcTemplate.update(updateSql, id);
                successCount++;

            } catch (Exception e) {
                failCount++;
                logger.error("处理记录 [{}] 出现异常", id, e);
                XxlJobLogger.log("处理记录 [{0}] 出现异常: {1}", id, e.getMessage());
                // 清理写入失败的临时文件，避免占用存储
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            }
            // 注意：临时文件由 Python Worker 处理后自行清理，或由 LocalIngestStrategy 上传 MinIO 后保留 MinIO 路径
        }

        XxlJobLogger.log("批次任务完成！总共 {0} 条，成功 {1}，失败 {2}", records.size(), successCount, failCount);
        return failCount > 0 && successCount == 0 ? ReturnT.FAIL : ReturnT.SUCCESS;
    }
}
