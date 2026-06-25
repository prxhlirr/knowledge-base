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

    /**
     * 存储文件（带业务分类），用于 MinIO 路径分级：{businessCategory}/yyyy/MM/dd/{文件名}_{uuid}.{ext}。
     * 默认实现忽略 businessCategory，委托旧方法（兼容本地存储等不分级实现）。
     *
     * @param inputStream      文件流
     * @param originalName     原始文件名（带后缀）
     * @param businessCategory 业务分类（如 doc/html/upload），作为 object key 首级目录；null 走默认
     * @return 存储后的路径或预签名 URL
     */
    default String store(InputStream inputStream, String originalName, String businessCategory) {
        return store(inputStream, originalName);
    }
}
