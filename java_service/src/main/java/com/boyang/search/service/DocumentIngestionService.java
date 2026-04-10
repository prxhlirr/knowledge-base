package com.boyang.search.service;

import com.boyang.search.entity.DocumentMetadata;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

/**
 * 统一核心文档接入服务接口
 * 定义 HTTP上传、URL直链、本地目录、远端 SFTP 四种数据来源的文档接入规范。
 */
public interface DocumentIngestionService {

    /**
     * 方式一：单/多文件 HTTP 接口直传
     */
    List<DocumentMetadata> ingestFromHttp(MultipartFile[] files) throws Exception;

    /**
     * 方式二：URL 直链下载转存至 MinIO
     */
    DocumentMetadata ingestFromUrl(String url) throws Exception;

    /**
     * 方式三：本地目录扫描读取并转存至 MinIO
     */
    List<DocumentMetadata> ingestFromLocalDirectory(String directoryPath) throws Exception;

    /**
     * 方式四：SSH/SFTP 目录文件拉取并转存至 MinIO
     */
    List<DocumentMetadata> ingestFromSftp(String host, int port, String user, String password, String remoteDir) throws Exception;
}
