package com.boyang.search.entity;

import lombok.Data;
import lombok.Builder;

/**
 * 统一的文件元数据结构
 * 记录文档接入模块的通用元信息
 */
@Data
@Builder
public class DocumentMetadata {
    /** 原始文件名 */
    private String originalFilename;
    /** MinIO 对象存储名称 */
    private String objectName;
    /** 文件来源 (LOCAL, HTTP, URL, SFTP) */
    private String source;
    /** 文件大小 */
    private Long size;
    /** 扩展名 */
    private String ext;
    /** 下载或原文地址 (如果是URL/SFTP来源则保留原地址) */
    private String originUrl;
}
