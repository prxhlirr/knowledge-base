package com.boyang.search.service;

import java.io.InputStream;

/**
 * 通用存储服务接口，支持本地存储与 MinIO 扩展
 */
public interface StorageService {
    /**
     * 存储文件并返回持久化后的绝对路径或标识
     * @param inputStream 文件流
     * @param originalName 原始文件名（带后缀）
     * @return 存储后的路径
     */
    String store(InputStream inputStream, String originalName);
}
