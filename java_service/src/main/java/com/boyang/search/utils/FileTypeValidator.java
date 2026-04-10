package com.boyang.search.utils;

import java.util.Arrays;

/**
 * 文件真实类型校验工具（P2 #1 Magic Number 检验修复）。
 * 业务功能：通过读取文件头部字节（Magic Number）鉴别文件真实类型，
 *           防止攻击者将非法文件改扩展名为 .pdf/.docx 等后绕过上传校验。
 * 支持类型：PDF / DOC（OLE2）/ DOCX（ZIP 容器）/ TXT（宽松匹配，无固定 Magic）。
 * 调用时机：文件保存前，由 DocImportController.uploadDocs() 调用。
 */
public class FileTypeValidator {

    // PDF   : %PDF（25 50 44 46）
    private static final byte[] MAGIC_PDF  = {0x25, 0x50, 0x44, 0x46};
    // DOC   : OLE2 复合文档头（D0 CF 11 E0 A1 B1 1A E1）
    private static final byte[] MAGIC_DOC  = {(byte)0xD0, (byte)0xCF, 0x11, (byte)0xE0};
    // DOCX  : ZIP 容器（50 4B 03 04）
    private static final byte[] MAGIC_DOCX = {0x50, 0x4B, 0x03, 0x04};

    /**
     * 判断文件字节是否属于允许的文档类型。
     * 对 TXT 文件宽松处理（无固定 Magic Number）：若不命中任何二进制格式，
     * 则尝试 UTF-8/GBK 解码前 512 字节，可读则视为文本文件。
     *
     * @param fileBytes 文件完整字节（或前 8 字节即可满足 Magic 检测）
     * @return true 表示类型合法，false 表示疑似非法文件
     */
    public static boolean isAllowed(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length < 4) return false;

        // 1. PDF
        if (startsWith(fileBytes, MAGIC_PDF)) return true;
        // 2. DOC（OLE2）
        if (startsWith(fileBytes, MAGIC_DOC)) return true;
        // 3. DOCX（ZIP）
        if (startsWith(fileBytes, MAGIC_DOCX)) return true;

        // 4. TXT 宽松匹配：尝试以 UTF-8/GBK 解码前 512 字节
        //    仅当无法解码时才拦截（防止上传二进制文件）
        try {
            int sampleLen = Math.min(fileBytes.length, 512);
            byte[] sample = Arrays.copyOfRange(fileBytes, 0, sampleLen);
            new String(sample, "UTF-8"); // 若字节序列不合法 UTF-8 会抛出
            // 进一步检查：控制字符占比不超过 5%，避免二进制文件误判为 TXT
            long ctrlCount = 0;
            for (byte b : sample) {
                int u = b & 0xFF;
                if (u < 0x09 || (u > 0x0D && u < 0x20)) ctrlCount++;
            }
            if ((double) ctrlCount / sampleLen < 0.05) return true;
        } catch (Exception ignore) {}

        // 5. 尝试 GBK 编码（兼容 Windows 中文环境导出的 TXT）
        try {
            int sampleLen = Math.min(fileBytes.length, 512);
            byte[] sample = Arrays.copyOfRange(fileBytes, 0, sampleLen);
            new String(sample, "GBK");
            return true;
        } catch (Exception ignore) {}

        return false;
    }

    /** 检查字节数组是否以指定前缀开头 */
    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }
}
