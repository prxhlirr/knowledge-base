package com.boyang.search.strategy.ingest;

import com.boyang.search.entity.SysDocBatch;
import com.boyang.search.model.DocIngestRequest;
import com.boyang.search.service.DocIndexRoutingService;
import com.boyang.search.service.KbDocRegistryService;
import com.boyang.search.service.StorageService;
import com.boyang.search.utils.ContentHashUtils;
import com.boyang.search.utils.DocumentTextNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 抽象入库策略：所有入库入口（UPLOAD/LOCAL/URL/SFTP）的公共基类。
 * <p>
 * [第一性原理统一] 四项正确性维度收敛到本类的公共管线 {@link #processFile}，
 * 子类只负责「文件来源」（提供可重读的 InputStream 与原始名），不再各自处理
 * 去重/命名/存储分级/hash，消除跨入口分裂：
 *   - 命名归一：{@link #sanitizeFilename}（跨入口可比的 source_name）
 *   - 内容标识：full_hash（全文件 SHA-256），唯一真相源
 *   - 前置去重：{@link KbDocRegistryService#existsByFullHash}，发生在 store 之前（杜绝存储污染）
 *   - 分级存储：{@link #resolveBusinessCategory}（物理布局=逻辑归属）
 * 可保留�    /** [统一存储] 所有子类共享（上移自各子类） */
    public abstract class AbstractIngestStrategy implements IngestStrategy {

    protected static final Logger log = LoggerFactory.getLogger(AbstractIngestStrategy.class);

    @Autowired
    protected DocIndexRoutingService docIndexRoutingService;

    @Autowired
    protected StorageService storageService;

    /** [统一去重] 所有子类共享（上移自 Upload/Local） */
    @Autowired
    protected KbDocRegistryService kbDocRegistryService;

    @org.springframework.beans.factory.annotation.Value("${doc.upload.max-file-size:104857600}")
    protected long maxFileSizeBytes;

    // ── 统一公共管线 ──────────────────────────────────────────────

    /** 可重复提供的输入流（算 hash 与 store 各读一次） */
    @FunctionalInterface
    public interface InputStreamSupplier {
        InputStream get() throws Exception;
    }

    /**
     * [统一公共管线] 所有入库入口的单文件处理。统一四项正确性维度。
     * <p>
     * 流程：
     *   1. 读取文件字节并限制最大大小与魔数校验；
     *   2. {@link #sanitizeFilename} → 跨入口可比 source_name；
     *   3. full_hash（优先复用 req.fullHash，否则流式 computeFull）；
     *   4. existsByFullHash 前置去重 → 命中跳过（不 store）；
     *   5. store(is, safeName, businessCategory) → 统一分级存储；
     *   6. buildTaskInfo + 透传 fullHash（Python 回调写 registry.full_hash）。
     *
     * @return taskInfo；去重命中或处理失败返回 null（调用方不计入结果集）
     */
    protected Map<String, String> processFile(InputStreamSupplier supplier, String originalName,
                                              DocIngestRequest req, SysDocBatch batch) {
        if (supplier == null || batch == null) return null;
        String safeName = sanitizeFilename(originalName);

        // 0. 读取文件字节并进行大小与魔数校验
        byte[] bytes;
        try (InputStream is = supplier.get()) {
            bytes = org.springframework.util.StreamUtils.copyToByteArray(is);
        } catch (Exception e) {
            synchronized (batch) { synchronized (batch) { batch.setErrorCount(batch.getErrorCount() + 1); } }
            log.error("[DocIngest] 读取文件字节失败 name={} err={}", safeName, e.getMessage());
            return null;
        }

        if (bytes.length > this.maxFileSizeBytes) {
            synchronized (batch) { synchronized (batch) { batch.setErrorCount(batch.getErrorCount() + 1); } }
            log.warn("[DocIngest] 文件大小超过限制跳过: {} size={}MB",
                    safeName, bytes.length / 1024L / 1024L);
            return null;
        }

        if (!com.boyang.search.utils.FileTypeValidator.isAllowed(bytes)) {
            synchronized (batch) { synchronized (batch) { batch.setErrorCount(batch.getErrorCount() + 1); } }
            log.warn("[DocIngest] 文件类型校验不通过: {}", safeName);
            return null;
        }

        // 1. full_hash：优先复用调用方预算值（如 doc 同步已下载算过），否则流式计算
        String fullHash = req != null ? req.getFullHash() : null;
        if (fullHash == null || fullHash.isEmpty() || "unknown".equals(fullHash)) {
            try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
                fullHash = ContentHashUtils.computeFull(bais);
            } catch (Exception e) {
                synchronized (batch) { synchronized (batch) { batch.setErrorCount(batch.getErrorCount() + 1); } }
                log.error("[DocIngest] 计算 full_hash 失败 name={} err={}", safeName, e.getMessage());
                return null;
            }
        }

        // 2. 鍓嶇疆鍘婚噸锛堢粺涓€ full_hash锛屽彂鐢熷湪 store 涔嬪墠锛屾潨缁濆瓨鍌ㄦ薄鏌擄級
        try {
            if (kbDocRegistryService.existsByFullHash(fullHash)) {
                synchronized (batch) { synchronized (batch) { batch.setErrorCount(batch.getErrorCount() + 1); } }
                log.info("[DocIngest] full_hash 閲嶅璺宠繃(涓嶅叆搴? name={} hash={}", safeName, fullHash);
                return null;
            }
        } catch (Exception e) {
            // 鍘婚噸鏌ヨ寮傚父鏃朵繚瀹堟斁琛岋紙瀹佸彲閲嶅鍏ュ簱锛屼笉鍙鏉€锛夛紝鐢卞敮涓€绾︽潫鍏滃簳
            log.warn("[DocIngest] full_hash 鍘婚噸鏌ヨ寮傚父锛屼繚瀹堟斁琛?name={} err={}", safeName, e.getMessage());
        }

        // 3. 鍒嗙骇瀛樺偍锛堢粺涓€ businessCategory锛塦r
        String savedPath;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            savedPath = storageService.store(bais, safeName, resolveBusinessCategory(req));
        } catch (Exception e) {
            synchronized (batch) { synchronized (batch) { batch.setErrorCount(batch.getErrorCount() + 1); } }
            log.error("[DocIngest] 瀛樺偍澶辫触 name={} err={}", safeName, e.getMessage());
            return null;
        }

        // 4. 组装 taskInfo + 透传 full_hash（content_hash 留空由 Python 计算，保留前 8K 作预筛索引）
        Map<String, String> info = buildTaskInfo(savedPath, safeName, req, "");
        info.put("fullHash", fullHash);
        return info;
    }

    /**
     * [统一命名] 跨入口一致的 source_name 归一：取文件名 + 非字母数字._- 替换为 _ + 截断 200。
     * 上移自 UploadIngestStrategy，所有入口共用，保证 findLatest(safeName) 跨入口可比。
     */
    protected static String sanitizeFilename(String original) {
        if (original == null || original.isEmpty()) {
            return "unknown";
        }
        String name = Paths.get(original).getFileName().toString();
        name = DocumentTextNormalizer.normalizeFilename(name);
        name = name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    /**
     * [统一存储分级] 按来源系统推导 MinIO object key 的业务首级目录。取值域固化。
     * 上移自 LocalIngestStrategy，所有入口共用。
     */
    protected static String resolveBusinessCategory(DocIngestRequest req) {
        if (req == null) return "upload";
        String src = req.getSourceSystem();
        if (src == null || src.isEmpty()) return "upload";
        switch (src) {
            case "DB_DOC_SYNC":  return "doc";
            case "DB_HTML_SYNC": return "html";
            case "SFTP":         return "sftp";
            case "URL":          return "url";
            default:             return "upload";
        }
    }

    /**
     * 将存储好的文档实体构建成需要派发给后续流程的参数集合。
     */
    protected Map<String, String> buildTaskInfo(String savedPath, String fileName, DocIngestRequest req, String contentHash) {
        HashMap<String, String> info = new HashMap<>();
        info.put("path", savedPath);
        info.put("name", fileName);

        // [动态路由] targetIndex 决策：显式非默认索引优先，否则按 tag 路由，再否则 fallback
        String targetIndex = req.getTargetIndex();
        if (targetIndex == null || targetIndex.isEmpty() || "kb_document_v1".equals(targetIndex)) {
            targetIndex = docIndexRoutingService.route(req.getTag());
        }
        info.put("targetIndex", targetIndex);

        info.put("visibility", req.getVisibility() != null ? req.getVisibility() : "INTERNAL");
        info.put("deptCode", req.getDeptCode() != null ? req.getDeptCode() : "");
        info.put("tag", DocumentTextNormalizer.normalizeMetadataText(req.getTag()));
        info.put("unit", DocumentTextNormalizer.normalizeMetadataText(req.getUnit()));
        info.put("docNumber", DocumentTextNormalizer.normalizeMetadataText(req.getDocNumber()));
        info.put("owner", DocumentTextNormalizer.normalizeMetadataText(req.getOwner()));
        info.put("searchQueries", DocumentTextNormalizer.normalizeMetadataText(req.getSearchQueries()));
        info.put("publishTime", nvl(req.getPublishTime()));
        info.put("sourceSystem", nvl(req.getSourceSystem()));
        info.put("contentHash", contentHash != null ? contentHash : "");
        info.put("skipFirstPage", req.isSkipFirstPage() ? "true" : "false");
        return info;
    }

    protected static String nvl(String s) {
        return s != null ? s : "";
    }
}
