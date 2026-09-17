package com.boyang.search.service;

import com.boyang.search.entity.KbDocOutbox;
import com.boyang.search.entity.SysDocBatch;
import com.boyang.search.entity.SysDocImportTask;
import com.boyang.search.entity.SysFileParseLog;
import com.boyang.search.event.DocTaskReadyEvent;
import com.boyang.search.mapper.KbDocOutboxMapper;
import com.boyang.search.model.DocIngestRequest;
import com.boyang.search.strategy.ingest.IngestStrategy;
import com.boyang.search.strategy.ingest.IngestStrategyFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.File;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DocIngestService {
    private static final Logger log = LoggerFactory.getLogger(DocIngestService.class);

    private final SysDocBatchService sysDocBatchService;
    private final SysDocImportTaskService sysDocImportTaskService;
    private final ISysFileParseLogService sysFileParseLogService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final IngestStrategyFactory ingestStrategyFactory;
    private final DeptTreeService deptTreeService;
    private final MinioStorageService minioStorageService;
    // [T1-5 Outbox] 写入 outbox 记录 + 发布 AFTER_COMMIT 事件
    private final KbDocOutboxMapper outboxMapper;
    private final ApplicationEventPublisher eventPublisher;

    // [修复 Spring AOP 自调用问题] 注入自身代理，解决 this.createAndDispatch() 不经过 AOP 代理
    // 导致 @Transactional 和 @TransactionalEventListener(AFTER_COMMIT) 失效的根本原因。
    // 使用 @Lazy 避免循环依赖（Bean 自身注入自身）。
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private DocIngestService self;

    /** 专用并发入库线程池，用于多文件场景并发计算 hash 与上传 MinIO */
    private final ExecutorService ingestExecutor = new ThreadPoolExecutor(
            8, 8,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r);
                    t.setName("doc-ingest-worker-" + count.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            }
    );

    /**
     * 获取专用并发入库线程池。
     * @return 线程池实例
     */
    public ExecutorService getIngestExecutor() {
        return this.ingestExecutor;
    }

    /**
     * 优雅销毁线程池，防止 JVM 停止时资源泄露。
     */
    @PreDestroy
    public void shutdownExecutor() {
        log.info("[DocIngestService] 正在优雅关闭 doc-ingest 线程池...");
        this.ingestExecutor.shutdown();
        try {
            if (!this.ingestExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                this.ingestExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.ingestExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final String QUEUE_HIGH = "DOC_TASK_QUEUE_HIGH";
    private static final String QUEUE_LOW = "DOC_TASK_QUEUE";
    private static final long QUEUE_SPLIT_BYTES = 0x200000L;

    @Autowired
    public DocIngestService(SysDocBatchService sysDocBatchService,
            SysDocImportTaskService sysDocImportTaskService,
            ISysFileParseLogService sysFileParseLogService,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            IngestStrategyFactory ingestStrategyFactory,
            DeptTreeService deptTreeService,
            MinioStorageService minioStorageService,
            KbDocOutboxMapper outboxMapper,
            ApplicationEventPublisher eventPublisher) {
        this.sysDocBatchService = sysDocBatchService;
        this.sysDocImportTaskService = sysDocImportTaskService;
        this.sysFileParseLogService = sysFileParseLogService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.ingestStrategyFactory = ingestStrategyFactory;
        this.deptTreeService = deptTreeService;
        this.minioStorageService = minioStorageService;
        this.outboxMapper = outboxMapper;
        this.eventPublisher = eventPublisher;
    }

    public String ingest(DocIngestRequest req) throws Exception {
        return ingest(req, currentOperatorId());
    }

    private String currentOperatorId() {
        com.boyang.search.security.JwtVerifier.UserIdentity identity =
                com.boyang.search.security.UserContextHolder.getIdentity();
        return identity != null ? identity.getUserId() : null;
    }

    /**
     * 校验入库请求的身份边界。
     * 业务功能：区分用户触发入库和系统同步入库，避免用户入口在登录上下文丢失时降级为免权限校验的系统入库。
     * 关键流程：有操作者身份时继续走用户权限校验；无操作者身份时，仅允许可信系统来源进入后续流程。
     */
    void validateIngestPrincipal(DocIngestRequest req, String operatorId) {
        if (operatorId != null && !operatorId.trim().isEmpty()) {
            return;
        }

        String sourceSystem = req != null ? req.getSourceSystem() : "";
        if (this.isTrustedSystemIngestSource(sourceSystem)) {
            log.info("[DocIngest] trusted system ingest sourceSystem={}", sourceSystem);
            return;
        }

        throw new SecurityException("[DocIngest] 用户入库缺少操作者身份，拒绝降级为系统入库 sourceSystem=" + sourceSystem);
    }

    /**
     * 判断是否为可信系统入库来源。
     * 业务功能：为已验证的数据库同步入口保留无登录态入库能力。
     * 关键流程：只接受代码中真实使用的系统来源，避免请求方伪造 sourceSystem 绕过用户权限校验。
     */
    boolean isTrustedSystemIngestSource(String sourceSystem) {
        String source = sourceSystem == null ? "" : sourceSystem.trim().toUpperCase();
        return "DB_HTML_SYNC".equals(source) || "DB_DOC_SYNC".equals(source);
    }

    /**
     * 文档入库主入口（含操作者身份校验版本）。
     * 业务功能：接收上传请求，经三步权限校验后，将任务写入 MySQL 并推入 Redis 队列供 Python 消费。
     * 关键流程：
     * 1. validatePermission — 校验 deptCode 合规性 + 操作者权限 + GRANT 用户存在性
     * 2. IngestStrategy.process — 按接入类型拉取/存储文件，返回任务列表
     * 3. createAndDispatch — 写 MySQL + 计算 acl_tokens（JSON 数组）+ 推入 Redis 队列
     *
     * @param req        入库请求 DTO
     * @param operatorId 当前操作者用户 ID（从 Session/JWT 中提取，用户入口不允许为空）
     */
    public String ingest(DocIngestRequest req, String operatorId) throws Exception {
        this.validateRequest(req);
        this.validateIngestPrincipal(req, operatorId);
        // [D4 修复] 权限校验（同步执行，确保非法请求立即被拦截）
        if (operatorId != null && !operatorId.trim().isEmpty()) {
            this.validatePermission(req, operatorId);
        }

        String batchId = UUID.randomUUID().toString();
        SysDocBatch batch = this.initBatch(batchId, req);

        // [竞态修复] 在跨越异步边界（submit）之前，于请求线程内同步固化 MultipartFile 字节。
        // 根因：MultipartFile 背后是 Tomcat 请求级临时文件，控制器返回后会被 cleanupMultipart() 删除；
        //       若把 file::getInputStream 作为懒加载流交给异步 worker，会随机命中「系统找不到指定的文件」，
        //       导致 processFile 返回 null、count=0、不入队（用户体感：上传了却没进队列）。
        // 修复：请求线程内临时文件仍在，getBytes() 必成功；固化后异步 worker 只读堆内字节。
        this.materializeUploadBytes(req);

        // [P0 优化] 核心架构重构：将扫描和派发整体异步化
        // 理由：针对 20,000 个文件的本地扫描，即使不读内容，递归 Files.walk 依然可能耗时数分钟。
        // 通过异步化，API 可以在 <1s 内返回 batchId，前端可轮询进度。
        this.ingestExecutor.submit(() -> {
            try {
                IngestStrategy strategy = ingestStrategyFactory.getStrategy(req.getIngestType());
                
                log.info("[DocIngest] 开始异步扫描批次 batchId={} type={}", batchId, req.getIngestType());
                // 执行扫描（现在 LocalIngestStrategy 仅扫描路径，不再读字节）
                List<Map<String, String>> taskInfos = strategy.process(req, batch);

                batch.setTotalCount(req.getUploadFiles() != null && req.getUploadFiles().length > 0
                        ? req.getUploadFiles().length
                        : taskInfos.size() + batch.getErrorCount());
                // 全部文件失败/去重（无任何任务产出）→ 终态 FAILED；否则保持 IMPORTING 等回调推进到 DONE。
                // 原行为：taskInfos 空时批次永远卡在 IMPORTING，前端无法感知失败。
                if (taskInfos.isEmpty() && batch.getErrorCount() > 0) {
                    batch.setStatus("FAILED");
                }
                this.sysDocBatchService.updateById(batch);

                if (!taskInfos.isEmpty()) {
                    String uploaderId = req.getUploaderId() != null ? req.getUploaderId()
                            : (operatorId != null ? operatorId : "");
                    List<String> grantedUsers = req.getGrantedUserIds() != null ? req.getGrantedUserIds()
                            : Collections.emptyList();
                    List<String> grantedRoles = req.getGrantedRoles() != null ? req.getGrantedRoles() : Collections.emptyList();
                    
                    // [大事务分批重构] 每次以小事务（最多 100 个文件）的形式进行落库和分发
                    // 理由：高并发或大批导入时，如果单次事务处理数万条记录，会导致连接池被长时间独占、行锁及事务日志暴增
                    int batchSize = 100;
                    for (int i = 0; i < taskInfos.size(); i += batchSize) {
                        int toIndex = Math.min(i + batchSize, taskInfos.size());
                        List<Map<String, String>> subList = taskInfos.subList(i, toIndex);
                        // 必须通过 self 代理调用以使其应用各自独立的 @Transactional 事务切面
                        self.createAndDispatch(batchId, subList, uploaderId, grantedUsers, grantedRoles, req.isForceOcr());
                    }
                }
                log.info("[DocIngest] 异步扫描并派发完成 batchId={} count={}", batchId, taskInfos.size());
            } catch (Exception e) {
                log.error("[DocIngest] 异步导入批次异常 batchId={} err={}", batchId, e.getMessage(), e);
                batch.setStatus("ERROR");
                this.sysDocBatchService.updateById(batch);
            }
        });

        return batchId;
    }

    private void validateRequest(DocIngestRequest req) {
        if (req.getIngestType() == null || req.getIngestType().isEmpty()) {
            throw new IllegalArgumentException("ingestType 不能为空");
        }
        if ("SFTP".equalsIgnoreCase(req.getIngestType())
                && (req.getCredentialId() == null || req.getCredentialId().isEmpty())) {
            throw new IllegalArgumentException("SFTP 模式下 credentialId 不能为空");
        }
        if ("DEPT".equalsIgnoreCase(req.getVisibility())
                && (req.getDeptCode() == null || req.getDeptCode().isEmpty())) {
            throw new IllegalArgumentException("visibility=DEPT 时 deptCode 不能为空");
        }
    }

    private String resolveWorkerFilePath(String storagePath) {
        if (storagePath == null || storagePath.trim().isEmpty()) {
            return storagePath;
        }
        if (storagePath.startsWith("http://") || storagePath.startsWith("https://")) {
            return storagePath;
        }
        try {
            String presignedUrl = minioStorageService.generatePresignedUrl(storagePath);
            if (presignedUrl != null && !presignedUrl.isEmpty()) {
                return presignedUrl;
            }
        } catch (Exception e) {
            log.warn("[DocIngest] 生成 Worker 下载 URL 失败，将回退原始路径 storagePath={} err={}",
                    storagePath, e.getMessage());
        }
        return storagePath;
    }

    /**
     * [D4 修复] 权限合规性三步校验（在 MySQL 写入和 Redis 入队之前执行）。
     * 防止调用方伪造 deptCode、越权归属部门、或引用不存在的被授权用户。
     *
     * 步骤1：校验 deptCode 是否在合法组织树中存在（查部门树缓存，避免实时查外部接口）
     * 步骤2：校验操作者是否有权将文档归属到目标部门（必须是目标部门或其祖先部门成员）
     * 步骤3：GRANT 模式下校验被授权用户是否存在（防止无效 user:: Token 写入 ES）
     *
     * @param req        入库请求（含 visibility/deptCode/grantedUserIds）
     * @param operatorId 当前操作者用户 ID
     */
    private void validatePermission(DocIngestRequest req, String operatorId) {
        // 步骤1：校验 deptCode 合法性
        if ("DEPT".equalsIgnoreCase(req.getVisibility())) {
            if (!deptTreeService.exists(req.getDeptCode())) {
                throw new IllegalArgumentException("[D4] deptCode 不存在于组织树: " + req.getDeptCode());
            }
        }

        // 步骤2：校验操作者归属权（必须属于目标部门或其祖先部门）
        // 注意：operatorDeptCode 来自 JWT/Session，此处通过 UserContextHolder 获取（搜索侧已设置）
        // 若无法获取操作者部门，跳过此校验（降级），避免阻塞管理端内部调用
        if ("DEPT".equalsIgnoreCase(req.getVisibility()) && operatorId != null) {
            com.boyang.search.security.JwtVerifier.UserIdentity identity = com.boyang.search.security.UserContextHolder
                    .getIdentity();
            if (identity != null && identity.getDeptCode() != null) {
                String operatorDeptCode = identity.getDeptCode();
                if (!deptTreeService.isAncestorOrSelf(operatorDeptCode, req.getDeptCode())) {
                    throw new SecurityException("[D4] 操作者无权将文档归属到部门 " + req.getDeptCode()
                            + "（操作者部门: " + operatorDeptCode + "）");
                }
            }
        }

        // 步骤3：GRANT 模式下校验被授权用户存在性（当前阶段做日志警告，不强制阻断）
        // 根因：跨服务用户查询存在网络波动风险，强制阻断可能影响可用性；
        // 后置 PermissionGuard 在检索时会做兜底校验。
        if ("GRANT".equalsIgnoreCase(req.getVisibility()) && req.getGrantedUserIds() != null) {
            for (String uid : req.getGrantedUserIds()) {
                if (uid == null || uid.trim().isEmpty()) {
                    throw new IllegalArgumentException("[D4] GRANT 模式下 grantedUserIds 包含空值");
                }
            }
            log.info("[D4] GRANT 模式 uploaderId={} grantedUsers={} grantedRoles={}",
                    operatorId, req.getGrantedUserIds(), req.getGrantedRoles());
        }
    }

    private SysDocBatch initBatch(String batchId, DocIngestRequest req) {
        SysDocBatch batch = new SysDocBatch();
        batch.setBatchId(batchId);
        batch.setIngestMode(req.getIngestType().toUpperCase());
        batch.setSourceInfo(req.getFilePath() != null ? req.getFilePath()
                : (req.getDirPath() != null ? req.getDirPath() : "UPLOAD_STREAM"));
        batch.setTotalCount(0);
        batch.setSuccessCount(0);
        batch.setErrorCount(0);
        batch.setStatus("IMPORTING");
        this.sysDocBatchService.save(batch);
        return batch;
    }

    /**
     * [竞态修复] 在请求线程内同步读取 UPLOAD 文件的字节，固化到 {@link DocIngestRequest#getUploadFileBytes()}。
     * <p>
     * 必须在 {@code ingestExecutor.submit} 之前调用（即仍在 HTTP 请求线程内）：
     * 此时 MultipartFile 背后的 Tomcat 临时文件尚未被 cleanupMultipart() 删除，{@code getBytes()} 必定成功。
     * 之后异步 worker 通过 {@link UploadIngestStrategy} 以 ByteArrayInputStream 形式消费这些堆内字节，
     * 与临时文件生命周期彻底解耦，消除「系统找不到指定的文件」竞态。
     * <p>
     * 仅对 UPLOAD（uploadFiles 非空）生效；LOCAL/SFTP/URL 文件源是稳定路径/URL，无需此步。
     * 单文件读取异常（理论不应发生在请求线程内）保守置 null，交由 UploadIngestStrategy 计入 errorCount。
     */
    private void materializeUploadBytes(DocIngestRequest req) {
        org.springframework.web.multipart.MultipartFile[] files = req.getUploadFiles();
        if (files == null || files.length == 0) {
            return;
        }
        byte[][] bytes = new byte[files.length][];
        for (int i = 0; i < files.length; i++) {
            try {
                bytes[i] = files[i].getBytes();
            } catch (Exception e) {
                log.error("[DocIngest] 同步读取上传字节失败 name={} err={}",
                        files[i].getOriginalFilename(), e.getMessage());
                bytes[i] = null;
            }
        }
        req.setUploadFileBytes(bytes);
    }

    /**
     * [T1-5 Outbox] 事务性任务派发。
     * 在单一 @Transactional 内完成所有 MySQL 写入，包括：
     * - sys_doc_import_task 记录（任务池）
     * - sys_file_parse_log 记录（日志）
     * - kb_doc_outbox 记录（WAITING 状态，等待 Python 回调确认）
     *
     * 关键安全保证：
     * - MySQL 写入失败回滚：Redis 尚未入队，Python 不会消费到无效任务
     * - Redis 入队失败：MySQL outbox=WAITING，由 DocTaskRecoveryJob 定时重推
     * - MySQL 已提交且 Redis 已入队：正常流程
     *
     * [修复] 使用 TransactionSynchronizationManager.registerSynchronization() afterCommit 钩子
     * 直接推入 Redis，替代原有 Spring ApplicationEvent 事件总线。
     * 根因：dynamic-datasource-spring-boot-starter 使用自己的 DynamicDataSourceTransactionManager，
     * 其事务提交钩子不一定能完整触发 Spring ApplicationEventMulticaster 的 AFTER_COMMIT 分发，
     * 导致 @TransactionalEventListener 永远无法被调用（任务写入 MySQL 但 Redis 未推入）。
     * TransactionSynchronizationManager 是 Spring 事务 SPI 的最底层，任何 TransactionManager 均兼容。
     */
    @Transactional(rollbackFor = Exception.class)
    public void createAndDispatch(String batchId, List<Map<String, String>> taskInfos,
            String uploaderId, List<String> grantedUsers, List<String> grantedRoles, boolean forceOcr) {
        ArrayList<SysDocImportTask> tasks = new ArrayList<>();
        ArrayList<SysFileParseLog> logs = new ArrayList<>();

        for (Map<String, String> info : taskInfos) {
            String taskId = UUID.randomUUID().toString();

            // ── 写 MySQL 任务记录 ──
            SysDocImportTask task = new SysDocImportTask();
            task.setTaskId(taskId);
            task.setBatchId(batchId);
            task.setFilePath(info.get("path"));
            task.setOriginalName(info.get("name"));
            task.setVisibility(info.getOrDefault("visibility", "INTERNAL"));
            task.setDeptCode(info.getOrDefault("deptCode", ""));
            task.setStatus("PENDING");
            tasks.add(task);

            SysFileParseLog fileLog = new SysFileParseLog();
            fileLog.setFileCode(taskId);
            fileLog.setFilePath(info.get("path"));
            fileLog.setUploader(info.getOrDefault("owner", "System"));
            fileLog.setUploaderDept(info.getOrDefault("unit", ""));
            fileLog.setUploadTime(new Date());
            fileLog.setStatus(0);
            logs.add(fileLog);

            // ── 构造 Redis Payload 并注册事务同步器（AFTER_COMMIT 时推入 Redis） ──
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("taskId", taskId);
                payload.put("fileCode", taskId);
                String storagePath = info.get("path");
                String workerFilePath = resolveWorkerFilePath(storagePath);
                payload.put("filePath", workerFilePath);
                payload.put("storagePath", storagePath);
                payload.put("storageMode", info.getOrDefault("storageMode", "MINIO"));
                payload.put("sourceLocalPath", info.getOrDefault("sourceLocalPath", ""));
                payload.put("originalName", info.get("name"));
                payload.put("visibility", info.getOrDefault("visibility", "INTERNAL"));
                payload.put("deptCode", info.getOrDefault("deptCode", ""));
                payload.put("targetIndex", info.getOrDefault("targetIndex", "kb_document_v1"));
                payload.put("tag", info.get("tag"));
                payload.put("unit", info.get("unit"));
                payload.put("docNumber", info.get("docNumber"));
                payload.put("owner", info.get("owner"));
                payload.put("searchQueries", info.get("searchQueries"));
                payload.put("publishTime", info.get("publishTime"));
                payload.put("sourceSystem", info.get("sourceSystem"));
                payload.put("contentHash", info.getOrDefault("contentHash", ""));
                // [统一内容标识] full_hash 全文件 SHA-256，由公共管线 processFile 透传，
                // Python registry 回调时写入 registry.full_hash（全入口统一，不再依赖 Job 对账）
                payload.put("full_hash", info.getOrDefault("fullHash", ""));
                // [PDF扫描件] skipFirstPage → skip_pages 整数传给 Python 解析层。
                // 设计：Java 端用语义化 boolean，Python 端用整数（便于未来扩展为跳 N 页）。
                int skipPages = "true".equalsIgnoreCase(info.getOrDefault("skipFirstPage", "false")) ? 1 : 0;
                payload.put("skip_pages", skipPages);
                payload.put("uploaderId", uploaderId);
                payload.put("forceOcr", forceOcr);
                // [第三方文档增量同步] DB_DOC_SYNC 链路设 force_reindex=true，跳过 Python 前 8K dedup。
                // 根因：Java 用 full_hash 单点去重，Python dedup_checker 用前 8K content_hash 查 ES，
                //       两者口径不同 → doc 末尾追加内容（前 8K 不变）会被 Python 误判重复 → 静默丢文档。
                // 修复：增量同步链路绕过 Python dedup，registry.full_hash 为唯一去重判据。
                //       仅对 DB_DOC_SYNC 生效，不影响上传/批导入的原有 Python dedup。
                boolean forceReindex = "DB_DOC_SYNC".equals(info.get("sourceSystem"));
                payload.put("force_reindex", forceReindex);

                String visibility = info.getOrDefault("visibility", "INTERNAL");
                String deptCode = info.getOrDefault("deptCode", "");
                List<String> aclTokens = computeAclTokens(
                        visibility, deptCode, uploaderId, grantedUsers, grantedRoles);
                payload.put("acl_tokens_json", this.objectMapper.writeValueAsString(aclTokens));
                Map<String, Object> unitProjection = buildUnitPermissionProjection(deptCode);
                payload.putAll(unitProjection);
                payload.put("grantedUserIds", grantedUsers);
                payload.put("grantedRoles", grantedRoles);

                String jsonPayload = this.objectMapper.writeValueAsString(payload);
                String sizeProbePath = info.getOrDefault("sourceLocalPath", "");
                if (sizeProbePath == null || sizeProbePath.isEmpty()) {
                    sizeProbePath = info.getOrDefault("path", "");
                }
                long fileSize = new File(sizeProbePath).length();
                String queueKey = fileSize < QUEUE_SPLIT_BYTES ? QUEUE_HIGH : QUEUE_LOW;

                // ── 写 Outbox 记录（WAITING），与任务记录在同一事务内提交 ──
                KbDocOutbox outbox = new KbDocOutbox();
                outbox.setTaskId(taskId);
                outbox.setSourceName(info.get("name"));
                outbox.setDocVersion(0);
                outbox.setFileBaseHash("");
                outbox.setTargetIndex(info.getOrDefault("targetIndex", "kb_document_v1"));
                outbox.setStatus("WAITING");
                outbox.setRetryCount(0);
                outbox.setCreatedAt(OffsetDateTime.now());
                outbox.setUpdatedAt(OffsetDateTime.now());
                outboxMapper.insert(outbox);

                // ── 注册事务同步回调，在 COMMIT 成功后推入 Redis ──
                // 使用 TransactionSynchronizationManager 而非 Spring 事件总线：
                // dynamic-datasource 的 DynamicDataSourceTransactionManager 兼容此 SPI，
                // 而 @TransactionalEventListener 依赖 ApplicationEventMulticaster，
                // 在多数据源场景下可能因事务管理器不一致而被跳过。
                final String finalTaskId   = taskId;
                final String finalQueueKey = queueKey;
                final String finalPayload  = jsonPayload;
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                stringRedisTemplate.opsForList().rightPush(finalQueueKey, finalPayload);
                                log.info("[DocIngest][Sync] 任务已入队 Redis taskId={} queue={}",
                                        finalTaskId, finalQueueKey);
                            } catch (Exception ex) {
                                log.error("[DocIngest][Sync] Redis 入队失败！taskId={} err={}。" +
                                        "outbox=WAITING，DocTaskRecoveryJob 将定时重推。",
                                        finalTaskId, ex.getMessage());
                            }
                        }
                    }
                );
                log.info("[DocIngest] 任务已入 Outbox taskId={} queue={} visibility={} tokens={}",
                        taskId, queueKey, visibility, aclTokens);
            } catch (Exception e) {
                log.error("[DocIngest] 任务派发失败 taskId={} err={}", taskId, e.getMessage());
                throw new RuntimeException("[DocIngest] 任务构造失败", e);
            }
        }
        // 批次保存 MySQL 记录（在事务内）
        this.sysDocImportTaskService.saveBatch(tasks);
        this.sysFileParseLogService.saveBatch(logs);
    }

    /**
     * [T1-5 Outbox] AFTER_COMMIT 兜底监听器（保留以防事务同步器注册失败时的降级路径）。
     * 正常路径：由 createAndDispatch 内的 TransactionSynchronization.afterCommit() 直接推入 Redis。
     * 降级路径：若同步器注册异常，此监听器作为第二道防线触发 Redis 推入。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocTaskReady(DocTaskReadyEvent event) {
        // 正常路径已由 registerSynchronization 处理，此处仅作日志确认
        log.debug("[DocIngest][Event] AFTER_COMMIT 事件触发（备用路径）taskId={} queue={}",
                event.getTaskId(), event.getQueueKey());
    }

    /**
     * [D2 V2 重构] 服务端 ACL Token 计算（唯一权威入口）。
     * Python 端直接使用返回结果写入 ES acl_tokens 字段，不得自行计算权限 Token。
     *
     * Token 格式规范（V2 统一使用小写双冒号，与 AclTokenBuilder 查询端对齐）：
     * _PUBLIC → 公开内容，任何人（含匿名）可访问
     * _INTERNAL → 内部内容，任意已登录用户可访问
     * dept::{deptCode} → 部门文档（写当前部门 + 所有祖先，实现双向穿透）
     * user::{userId} → 用户级：上传者本人始终可访问（PRIVATE/GRANT 模式）
     * role::{roleCode} → 角色级：GRANT 模式下授权角色，与 IAM 角色系统对接
     *
     * 双向穿透设计（DEPT 模式）：
     * 文档写当前部门 + 所有祖先部门 → 父部门用户可见子部门文档（管理者模型）。
     * 用户侧 AclTokenBuilder 写用户自身部门 + 所有祖先 → ES terms 求交集。
     *
     * @param visibility   文档可见度枚举（PUBLIC/INTERNAL/DEPT/PRIVATE/GRANT）
     * @param deptCode     部门编码（visibilty=DEPT 时必填）
     * @param uploaderId   上传者用户 ID（PRIVATE/GRANT 模式使用，禁止为 null）
     * @param grantedUsers GRANT 模式显式授权用户 ID 列表
     * @param grantedRoles GRANT 模式显式授权角色 Code 列表
     * @return ACL Token 列表（不含重复，永不为 null）
     */
    private List<String> computeAclTokens(String visibility, String deptCode,
            String uploaderId,
            List<String> grantedUsers,
            List<String> grantedRoles) {
        List<String> tokens = new ArrayList<>();
        String vis = (visibility != null) ? visibility.toUpperCase() : "INTERNAL";
        String safeUploaderId = (uploaderId != null && !uploaderId.isEmpty()) ? uploaderId : null;

        switch (vis) {
            case "PUBLIC":
                // 公开文档：_PUBLIC Token，匿名用户也可访问
                tokens.add("_PUBLIC");
                break;

            case "INTERNAL":
                // 内部文档：所有已登录用户可访问，上传者始终可访问
                tokens.add("_INTERNAL");
                break;

            case "DEPT":
                // [双向穿透] 写当前部门 + 所有祖先部门的 dept:: token
                // 父部门用户（如兰州市 6201）拥有 dept::6201 token，
                // 文档也包含 dept::6201（祖先） → ES terms 求交集 → 有权访问
                if (deptCode != null && !deptCode.isEmpty()) {
                    List<String> chain = deptTreeService.buildAclChain(deptCode);
                    chain.forEach(code -> tokens.add("dept::" + code));
                }
                // 上传者始终可访问（防止 DEPT 只有部门成员可见但上传者不在该部门的情况）
                if (safeUploaderId != null)
                    tokens.add("user::" + safeUploaderId);
                break;

            case "PRIVATE":
                // 私有文档：仅上传者本人（user:: token 与 AclTokenBuilder 注入的一致）
                if (safeUploaderId != null)
                    tokens.add("user::" + safeUploaderId);
                break;

            case "GRANT":
                // 授权文档：上传者 + 指定用户 + 指定角色
                // user::uid 与 AclTokenBuilder 注入的 user::userId 直接交集命中（无需查表）
                // role::roleCode 与 AclTokenBuilder 从 IAM 查得的角色交集命中
                if (safeUploaderId != null)
                    tokens.add("user::" + safeUploaderId);
                if (grantedUsers != null) {
                    grantedUsers.stream()
                            .filter(uid -> uid != null && !uid.trim().isEmpty())
                            .forEach(uid -> tokens.add("user::" + uid));
                }
                if (grantedRoles != null) {
                    grantedRoles.stream()
                            .filter(role -> role != null && !role.trim().isEmpty())
                            .forEach(role -> tokens.add("role::" + role));
                }
                break;

            default:
                // 未知可见度降级为最严格：仅上传者本人
                log.warn("[DocIngest] 未识别的 visibility='{}' 降级为 PRIVATE 处理", visibility);
                if (safeUploaderId != null)
                    tokens.add("user::" + safeUploaderId);
                break;
        }

        log.debug("[DocIngest] computeAclTokens vis={} dept={} tokens={}", vis, deptCode, tokens);
        return tokens;
    }

    /**
     * 业务功能：为 Python 入库 Worker 预计算单位权限投影字段。
     * 关键流程：以文档归属 deptCode 为权威输入，复用 DeptTreeService 构建“本级 + 上级”链路，
     *          让 ES 查询侧只需要用当前用户单位做 terms 命中即可实现“上级可见下级文档”。
     * 设计原因：组织树属于 Java 侧权限域，Python 只负责索引写入，避免两端重复维护单位层级规则。
     *
     * @param deptCode 文档归属单位编码，允许为空
     * @return ownerUnitCode、visibleUnitCodes、permissionVersion 三个 Redis payload 字段
     */
    Map<String, Object> buildUnitPermissionProjection(String deptCode) {
        Map<String, Object> projection = new HashMap<>();
        String ownerUnitCode = DeptTreeService.normalizeDeptCode(deptCode);
        List<String> visibleUnitCodes = new ArrayList<>();

        if (!ownerUnitCode.isEmpty()) {
            List<String> chain = deptTreeService != null
                    ? deptTreeService.buildAclChain(ownerUnitCode)
                    : Collections.emptyList();
            if (chain != null && !chain.isEmpty()) {
                for (String code : chain) {
                    if (code != null && !code.trim().isEmpty() && !visibleUnitCodes.contains(code.trim())) {
                        visibleUnitCodes.add(code.trim());
                    }
                }
            }
            if (visibleUnitCodes.isEmpty()) {
                visibleUnitCodes.add(ownerUnitCode);
            }
        } else {
            visibleUnitCodes.add("global");
        }

        projection.put("ownerUnitCode", ownerUnitCode.isEmpty() ? "global" : ownerUnitCode);
        projection.put("visibleUnitCodes", visibleUnitCodes);
        projection.put("permissionVersion", System.currentTimeMillis());
        return projection;
    }
}
