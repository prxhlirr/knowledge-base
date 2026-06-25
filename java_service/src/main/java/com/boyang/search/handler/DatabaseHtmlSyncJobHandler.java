package com.boyang.search.handler;

import com.boyang.search.entity.KbDocRegistry;
import com.boyang.search.entity.KbDocSyncRecord;
import com.boyang.search.mapper.KbDocSyncRecordMapper;
import com.boyang.search.model.DocIngestRequest;
import com.boyang.search.service.DocIngestService;
import com.boyang.search.service.KbDocRegistryService;
import com.boyang.search.service.MinioStorageService;
import com.boyang.search.utils.ContentHashUtils;
import com.boyang.search.utils.DocumentTextNormalizer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 数据库内容抽取同步至知识库处理器。
 * <p>
 * 当前承载两类第三方文档增量同步：
 *   1. {@link #execute} —— HTML 内容同步（正文存数据库字段），已有逻辑。
 *   2. {@link #executeDocSync} —— doc 文档同步（文件本体在第三方只读业务表，给下载路径）。
 * <p>
 * doc 同步核心设计（详见 plan）：
 *   - 源表完全只读 → 状态机全部落在映射表 kb_doc_sync_record，源表纯 SELECT；
 *   - 增量发现：时间戳游标（源表 update_time）+ 映射表对比；
 *   - 去重：full_hash（全文件 SHA-256）单点预查 registry.full_hash；
 *   - 不丢：不立即标终态，对账按 full_hash 确认；失败/超时回退重试；
 *   - 消除跨层口径矛盾：DB_DOC_SYNC 链路 force_reindex=true 跳过 Python 前 8K dedup。
 * <p>
 * 另含 {@link #backfillFullHash} —— 历史已入库文档的 full_hash 一次性回填（上线前置）。
 */
@Component
public class DatabaseHtmlSyncJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseHtmlSyncJobHandler.class);
    /** 同步状态机常量 */
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_DISPATCHED = "DISPATCHED";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String SOURCE_SYSTEM_DOC = "DB_DOC_SYNC";

    /**
     * HTML 临时文件存储目录（容器内路径，与 Python Worker 共享 volume）。
     */
    @Value("${knowledge-base.extract.html-temp-dir:/data/applogs/kb/storage/temp}")
    private String htmlTempDir;

    // ─── doc 同步配置（源表只读，名/列名待业务确认，全部可配置） ───
    @Value("${knowledge-base.extract.doc-temp-dir:${knowledge-base.extract.html-temp-dir:/data/applogs/kb/storage/temp}}")
    private String docTempDir;

    /** doc 第三方源表名（完全只读） */
    @Value("${knowledge-base.sync.doc.source-table:temp_doc_parse}")
    private String docSourceTable;

    @Value("${knowledge-base.sync.doc.col-id:id}")
    private String docIdCol;
    @Value("${knowledge-base.sync.doc.col-update-time:update_time}")
    private String docUpdateTimeCol;
    @Value("${knowledge-base.sync.doc.col-download-url:download_url}")
    private String docDownloadUrlCol;
    @Value("${knowledge-base.sync.doc.col-file-name:file_name}")
    private String docFileNameCol;
    @Value("${knowledge-base.sync.doc.col-doc-number:doc_number}")
    private String docDocNumberCol;
    @Value("${knowledge-base.sync.doc.col-from-unit:from_unit}")
    private String docFromUnitCol;
    @Value("${knowledge-base.sync.doc.col-receiver:receiver}")
    private String docReceiverCol;
    @Value("${knowledge-base.sync.doc.col-receive-unit:receive_unit}")
    private String docReceiveUnitCol;

    @Value("${knowledge-base.sync.doc.batch-size:100}")
    private int docBatchSize;
    @Value("${knowledge-base.sync.doc.retry-max:5}")
    private int docRetryMax;
    /** DISPATCHED 超时回退 PENDING 的阈值（分钟） */
    @Value("${knowledge-base.sync.doc.dispatch-timeout-min:15}")
    private int docDispatchTimeoutMin;

    // 主数据源的 JdbcTemplate（查询第三方只读源表 + 原 HTML temp_html_parse）
    @Resource
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocIngestService docIngestService;

    @Autowired
    private KbDocSyncRecordMapper kbDocSyncRecordMapper;

    @Autowired
    private KbDocRegistryService kbDocRegistryService;

    @Autowired
    private MinioStorageService minioStorageService;

    // ════════════════════════════════════════════════════════════════════
    //  1. HTML 同步（已有逻辑，保持不动）
    // ════════════════════════════════════════════════════════════════════

    /**
     * XXL-JOB 调度入口：dbHtmlExtractJob
     */
    @XxlJob("dbHtmlExtractJob")
    public ReturnT<String> execute(String param) throws Exception {
        XxlJobLogger.log("启动 DatabaseHtmlSyncJob，HTML 临时目录: " + htmlTempDir);

        File tempDirFile = new File(htmlTempDir);
        if (!tempDirFile.exists()) {
            boolean created = tempDirFile.mkdirs();
            if (!created) {
                XxlJobLogger.log("❌ 临时目录创建失败: " + htmlTempDir);
                return ReturnT.FAIL;
            }
        }

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
            String title = DocumentTextNormalizer.normalizeMetadataText(
                    String.valueOf(record.getOrDefault("title", "未命名从库文档_" + id)));
            String htmlContent = DocumentTextNormalizer.normalizeContentIfFullyEncoded(
                    String.valueOf(record.getOrDefault("content", "")));
            String author = DocumentTextNormalizer.normalizeMetadataText(
                    String.valueOf(record.getOrDefault("user_name", "DB_BOT")));
            String pubTime = record.get("publish_time") != null ? String.valueOf(record.get("publish_time")) : null;
            String deptCode = record.get("org_code") != null ? String.valueOf(record.get("org_code")) : null;
            String unit = record.get("org_name") != null
                    ? DocumentTextNormalizer.normalizeMetadataText(String.valueOf(record.get("org_name")))
                    : null;

            if (htmlContent.trim().isEmpty()) {
                XxlJobLogger.log("记录 [{0}] HTML 内容为空，跳过", id);
                continue;
            }

            String safeTitle = title.replaceAll("[\\\\/:*?\"<>|]", "_");
            String fileName = safeTitle + ".html";
            File tempFile = new File(tempDirFile, "db_" + id + "_" + System.currentTimeMillis() + ".html");

            try {
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    fos.write(htmlContent.getBytes(StandardCharsets.UTF_8));
                }
                XxlJobLogger.log("记录 [{0}] HTML 已写入临时文件: {1}", id, tempFile.getAbsolutePath());

                DocIngestRequest req = new DocIngestRequest();
                req.setIngestType("LOCAL");
                req.setFilePath(tempFile.getAbsolutePath());
                req.setFileName(fileName);
                req.setVisibility("INTERNAL");
                req.setOwner(author);
                req.setDeptCode(deptCode);
                req.setUnit(unit);
                req.setSourceSystem("DB_HTML_SYNC");

                if (pubTime != null && !pubTime.trim().isEmpty() && !"null".equalsIgnoreCase(pubTime.trim())) {
                    req.setPublishTime(pubTime.length() >= 10 ? pubTime.substring(0, 10) : pubTime);
                }

                String batchId = docIngestService.ingest(req);
                XxlJobLogger.log("记录 [{0}] 入库成功，batchId={1}", id, batchId);

                jdbcTemplate.update(updateSql, id);
                successCount++;

            } catch (Exception e) {
                failCount++;
                logger.error("处理记录 [{}] 出现异常", id, e);
                XxlJobLogger.log("处理记录 [{0}] 出现异常: {1}", id, e.getMessage());
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            }
        }

        XxlJobLogger.log("批次任务完成！总共 {0} 条，成功 {1}，失败 {2}", records.size(), successCount, failCount);
        return failCount > 0 && successCount == 0 ? ReturnT.FAIL : ReturnT.SUCCESS;
    }

    // ════════════════════════════════════════════════════════════════════
    //  2. doc 同步（dbDocExtractJob，三段式：拉新 / 处理 / 对账）
    // ════════════════════════════════════════════════════════════════════

    /**
     * XXL-JOB 调度入口：dbDocExtractJob
     * <p>
     * 高频轮询（cron 由 XXL-JOB 控制台配置，建议 30s~1min 实现"实时"）。
     * 每轮三段式：
     *   Part A 游标拉新：按源表 update_time 拉新增/变更 → 映射表 UPSERT；
     *   Part B 处理 PENDING：抢占 → 下载 → 算 full_hash → 预查 → 入库；
     *   Part C 对账：DISPATCHED → registry 命中确认写 full_hash / 超时回退重试。
     */
    @XxlJob("dbDocExtractJob")
    public ReturnT<String> executeDocSync(String param) {
        ensureDir(docTempDir);

        int pulled = pullNewDocRecords();
        int processed = processPendingDocs();
        int reconciled = reconcileDispatched();

        XxlJobLogger.log("dbDocExtractJob 完成：拉新={0} 处理={1} 对账={2}",
                pulled, processed, reconciled);
        return ReturnT.SUCCESS;
    }

    /** Part A：时间戳游标拉新源表，UPSERT 映射表（CONFIRMED 回 PENDING 触发变更重检）。 */
    private int pullNewDocRecords() {
        OffsetDateTime lastTs = kbDocSyncRecordMapper.maxSourceUpdateTime(SOURCE_SYSTEM_DOC);
        java.sql.Timestamp lastTsParam = lastTs != null ? java.sql.Timestamp.from(lastTs.toInstant()) : null;

        String sql = "SELECT * FROM " + docSourceTable + " WHERE " + docUpdateTimeCol
                + " > ? ORDER BY " + docUpdateTimeCol + " LIMIT " + docBatchSize;

        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(sql, lastTsParam);
        } catch (Exception e) {
            XxlJobLogger.log("Part A 查询源表失败 table={0} err={1}", docSourceTable, e.getMessage());
            return 0;
        }
        if (rows == null || rows.isEmpty()) return 0;

        int count = 0;
        for (Map<String, Object> row : rows) {
            String sourceId = stringOf(row.get(docIdCol));
            OffsetDateTime updTime = toOffsetDateTime(row.get(docUpdateTimeCol));
            if (sourceId == null || sourceId.isEmpty()) continue;
            upsertDocSyncRecord(sourceId, updTime);
            count++;
        }
        return count;
    }

    /** UPSERT 映射表：不存在→PENDING 新增；存在→刷新 update_time，CONFIRMED 回 PENDING。 */
    private void upsertDocSyncRecord(String sourceId, OffsetDateTime updTime) {
        KbDocSyncRecord exist = kbDocSyncRecordMapper.selectOne(
                new LambdaQueryWrapper<KbDocSyncRecord>()
                        .eq(KbDocSyncRecord::getSourceTable, docSourceTable)
                        .eq(KbDocSyncRecord::getSourceId, sourceId));
        OffsetDateTime now = OffsetDateTime.now();
        if (exist == null) {
            KbDocSyncRecord r = new KbDocSyncRecord();
            r.setSourceSystem(SOURCE_SYSTEM_DOC);
            r.setSourceTable(docSourceTable);
            r.setSourceId(sourceId);
            r.setSourceUpdateTime(updTime);
            r.setSyncStatus(STATUS_PENDING);
            r.setRetryCount(0);
            r.setCreatedAt(now);
            r.setUpdatedAt(now);
            try {
                kbDocSyncRecordMapper.insert(r);
            } catch (Exception dup) {
                // 并发 UPSERT 兜底：唯一约束冲突说明已被其他节点插入，忽略
            }
        } else {
            LambdaUpdateWrapper<KbDocSyncRecord> uw = new LambdaUpdateWrapper<>();
            uw.eq(KbDocSyncRecord::getId, exist.getId())
                    .set(KbDocSyncRecord::getSourceUpdateTime, updTime)
                    .set(KbDocSyncRecord::getUpdatedAt, now);
            // CONFIRMED 记录被源表更新 → 回 PENDING 触发内容变更重检
            if (STATUS_CONFIRMED.equals(exist.getSyncStatus())) {
                uw.set(KbDocSyncRecord::getSyncStatus, STATUS_PENDING);
            }
            kbDocSyncRecordMapper.update(null, uw);
        }
    }

    /** Part B：抢占 PENDING → 下载 → 算 full_hash → 预查去重/入库。 */
    private int processPendingDocs() {
        List<KbDocSyncRecord> claimed = kbDocSyncRecordMapper.claimPending(SOURCE_SYSTEM_DOC, docBatchSize);
        if (claimed == null || claimed.isEmpty()) return 0;

        int success = 0;
        for (KbDocSyncRecord rec : claimed) {
            File tempFile = null;
            try {
                Map<String, Object> row = jdbcTemplate.queryForMap(
                        "SELECT * FROM " + docSourceTable + " WHERE " + docIdCol + " = ?", rec.getSourceId());

                String downloadUrl = stringOf(row.get(docDownloadUrlCol));
                String origName = stringOf(row.get(docFileNameCol));
                if (origName == null || origName.isEmpty()) origName = "doc_" + rec.getSourceId();
                origName = DocumentTextNormalizer.normalizeFilename(origName);
                if (downloadUrl == null || downloadUrl.trim().isEmpty()) {
                    throw new IllegalStateException("下载路径为空 sourceId=" + rec.getSourceId());
                }

                // 1. 下载 doc 到临时文件
                ensureDir(docTempDir);
                tempFile = new File(docTempDir, "db_" + rec.getSourceId() + "_" + System.currentTimeMillis() + ".doc");
                downloadDocFile(downloadUrl, tempFile);

                // 2. 算全文件 full_hash
                String fullHash = ContentHashUtils.computeFull(tempFile.toPath());
                String safeName = origName.replaceAll("[\\\\/:*?\"<>|]", "_");
                String uniqueName = safeName + "_" + (fullHash.length() >= 8 ? fullHash.substring(0, 8) : fullHash) + ".doc";

                // 3. 预查 registry.full_hash（含历史回填后的）→ 命中即已入库，CONFIRMED 跳过
                if (kbDocRegistryService.existsByFullHash(fullHash)) {
                    rec.setFullHash(fullHash);
                    rec.setFileName(uniqueName);
                    rec.setSyncStatus(STATUS_CONFIRMED);
                    rec.setUpdatedAt(OffsetDateTime.now());
                    kbDocSyncRecordMapper.updateById(rec);
                    XxlJobLogger.log("记录 [{0}] full_hash 命中已入库，跳过", rec.getSourceId());
                    success++;
                    continue;
                }

                // 4. 构造入库请求（DB_DOC_SYNC → createAndDispatch 自动设 force_reindex）
                DocIngestRequest req = new DocIngestRequest();
                req.setIngestType("LOCAL");
                req.setFilePath(tempFile.getAbsolutePath());
                req.setFileName(uniqueName);
                req.setVisibility("INTERNAL");
                req.setDocNumber(DocumentTextNormalizer.normalizeMetadataText(stringOf(row.get(docDocNumberCol))));
                req.setUnit(DocumentTextNormalizer.normalizeMetadataText(stringOf(row.get(docFromUnitCol))));          // 来文单位→unit
                req.setDeptCode(stringOf(row.get(docReceiveUnitCol)));   // 收文单位→deptCode
                req.setOwner(DocumentTextNormalizer.normalizeMetadataText(stringOf(row.get(docReceiverCol))));         // 收文人→owner
                req.setSourceSystem(SOURCE_SYSTEM_DOC);
                req.setFullHash(fullHash); // 公共管线复用，避免 LocalIngestStrategy 重算 full_hash

                String batchId = docIngestService.ingest(req);

                // 5. 映射表写 full_hash + batch_id，保持 DISPATCHED（不标终态，等 Part C 对账）
                rec.setFullHash(fullHash);
                rec.setFileName(uniqueName);
                rec.setBatchId(batchId);
                rec.setUpdatedAt(OffsetDateTime.now());
                kbDocSyncRecordMapper.updateById(rec);
                success++;

            } catch (Exception e) {
                logger.error("doc 同步处理失败 sourceId={}", rec.getSourceId(), e);
                markFailedOrRetry(rec, "处理异常: " + e.getMessage());
            } finally {
                // 临时文件由 LocalIngestStrategy 上传 MinIO 后不再需要；异常时清理
                if (tempFile != null && tempFile.exists()) {
                    try { tempFile.delete(); } catch (Exception ignore) {}
                }
            }
        }
        return success;
    }

    /** Part C：对账 DISPATCHED → registry 命中则 CONFIRMED 并写 full_hash；超时则回退重试。 */
    private int reconcileDispatched() {
        List<KbDocSyncRecord> dispatched = kbDocSyncRecordMapper.selectList(
                new LambdaQueryWrapper<KbDocSyncRecord>()
                        .eq(KbDocSyncRecord::getSyncStatus, STATUS_DISPATCHED)
                        .eq(KbDocSyncRecord::getSourceSystem, SOURCE_SYSTEM_DOC));
        if (dispatched == null || dispatched.isEmpty()) return 0;

        int confirmed = 0;
        OffsetDateTime now = OffsetDateTime.now();
        for (KbDocSyncRecord rec : dispatched) {
            KbDocRegistry reg = rec.getFileName() != null
                    ? kbDocRegistryService.findLatest(rec.getFileName()) : null;
            if (reg != null) {
                // Python 已入库：写 registry.full_hash（Java 闭环）+ CONFIRMED
                kbDocRegistryService.updateFullHashBySourceName(rec.getFileName(), rec.getFullHash());
                rec.setSyncStatus(STATUS_CONFIRMED);
                rec.setUpdatedAt(now);
                kbDocSyncRecordMapper.updateById(rec);
                confirmed++;
            } else {
                // 未入库：超时则回退重试
                if (rec.getDispatchedAt() != null
                        && rec.getDispatchedAt().plusMinutes(docDispatchTimeoutMin).isBefore(now)) {
                    markFailedOrRetry(rec, "DISPATCHED 超时未入库");
                }
                // 未超时则保持 DISPATCHED，等下次对账
            }
        }
        return confirmed;
    }

    /** 失败处理：retry_count++ ，未超上限回 PENDING，超上限置 FAILED。 */
    private void markFailedOrRetry(KbDocSyncRecord rec, String msg) {
        int retry = rec.getRetryCount() == null ? 0 : rec.getRetryCount();
        retry += 1;
        rec.setRetryCount(retry);
        rec.setErrorMsg(msg != null && msg.length() > 1000 ? msg.substring(0, 1000) : msg);
        rec.setSyncStatus(retry >= docRetryMax ? STATUS_FAILED : STATUS_PENDING);
        rec.setUpdatedAt(OffsetDateTime.now());
        kbDocSyncRecordMapper.updateById(rec);
    }

    /**
     * HTTP 下载 doc 文件到本地（JDK 原生 + 超时）。
     * 绕过 UrlIngestStrategy 的内网安全检查（第三方下载路径多为内网）。
     * TODO(待确认)：若第三方下载需鉴权（token/cookie），在此追加请求头。
     */
    private void downloadDocFile(String fileUrl, File target) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(fileUrl).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "KB-DocSync-Job");
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("doc 下载失败 HTTP " + code + " url=" + fileUrl);
            }
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  3. 历史回填（dbFullHashBackfillJob，上线前置一次性执行）
    // ════════════════════════════════════════════════════════════════════

    /**
     * XXL-JOB 调度入口：dbFullHashBackfillJob（手动触发一次）。
     * <p>
     * 扫 registry 中 full_hash 为空的历史文档，按 storage_path 从 MinIO 下载原始文件，
     * 算全文件 SHA-256 回填 full_hash。回填后增量同步预查能命中历史文档，避免重复入库。
     * 前提：历史文档 MinIO 原始文件仍保留（storage_path 有效）。
     */
    @XxlJob("dbFullHashBackfillJob")
    public ReturnT<String> backfillFullHash(String param) {
        List<KbDocRegistry> pending = kbDocRegistryService.findLatestByFullHashNull();
        if (pending == null || pending.isEmpty()) {
            XxlJobLogger.log("无待回填 full_hash 的历史文档");
            return ReturnT.SUCCESS;
        }
        int ok = 0, fail = 0;
        for (KbDocRegistry reg : pending) {
            String storagePath = reg.getStoragePath();
            if (storagePath == null || storagePath.trim().isEmpty()) {
                fail++;
                continue;
            }
            try (InputStream is = minioStorageService.downloadStream(storagePath)) {
                String fullHash = ContentHashUtils.computeFull(is);
                if (!"unknown".equals(fullHash)) {
                    kbDocRegistryService.updateFullHashById(reg.getId(), fullHash);
                    ok++;
                } else {
                    fail++;
                }
            } catch (Exception e) {
                fail++;
                logger.warn("回填 full_hash 失败 id={} source={} err={}",
                        reg.getId(), reg.getSourceName(), e.getMessage());
            }
        }
        XxlJobLogger.log("dbFullHashBackfillJob 完成：成功={0} 失败={1}", ok, fail);
        return ReturnT.SUCCESS;
    }

    // ════════════════════════════════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════════════════════════════════

    private void ensureDir(String dir) {
        File d = new File(dir);
        if (!d.exists()) d.mkdirs();
    }

    private static String stringOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 源表 update_time 列值（java.sql.Timestamp/LocalDateTime/Date 等）→ OffsetDateTime。 */
    private static OffsetDateTime toOffsetDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof OffsetDateTime) return (OffsetDateTime) o;
        if (o instanceof java.sql.Timestamp)
            return ((java.sql.Timestamp) o).toLocalDateTime().atOffset(ZoneOffset.ofHours(8));
        if (o instanceof java.time.LocalDateTime)
            return ((java.time.LocalDateTime) o).atOffset(ZoneOffset.ofHours(8));
        if (o instanceof java.util.Date)
            return ((java.util.Date) o).toInstant().atOffset(ZoneOffset.ofHours(8));
        return null;
    }
}
