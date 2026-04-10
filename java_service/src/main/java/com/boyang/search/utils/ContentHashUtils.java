package com.boyang.search.utils;

import java.security.MessageDigest;

/**
 * 文件内容 SHA-256 指纹工具类。
 * <p>
 * 业务功能：为内容去重提供统一的哈希计算入口。
 * 算法策略：只取文件前 MIN(size, 8192) 字节计算 SHA-256，
 *           绝大多数文件的内容差异已体现在文件头，速度远快于全文件哈希，
 *           且与 Python rag_pipeline 侧的 compute_hash() 算法保持一致。
 * <p>
 * 背景：原来 {@code DocImportController} 和 {@code DocIngestService} 各自持有
 *       完全相同的私有 {@code computeContentHash()} 方法，违反 DRY 原则；
 *       此工具类统一消灭代码克隆（参见重构任务 P1-Sprint4）。
 */
public final class ContentHashUtils {

    /**
     * 最大读取字节数（8 KB），与 Python 侧保持一致。
     * 根因：如修改此值必须同步修改 Python ai_service/core/indexing/dedup_checker.py
     */
    private static final int MAX_HASH_BYTES = 8192;

    // 工具类禁止实例化
    private ContentHashUtils() {}

    /**
     * 计算字节数组前 MIN(length, 8192) 字节的 SHA-256 十六进制字符串。
     * <p>
     * 关键设计：
     *   - 与 DocIngestService / DocImportController 原有算法完全等价（无语义变更）
     *   - 计算失败时返回空字符串（不抛异常），由上层以文本回退哈希兜底
     *
     * @param bytes 文件全量或部分字节数组
     * @return SHA-256 hex 小写字符串；计算失败时返回空字符串 ""
     */
    public static String compute(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "unknown";
        try {
            int len = Math.min(bytes.length, MAX_HASH_BYTES);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(bytes, 0, len);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
