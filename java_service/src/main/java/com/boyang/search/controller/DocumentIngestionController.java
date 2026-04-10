package com.boyang.search.controller;

import com.boyang.search.entity.DocumentMetadata;
import com.boyang.search.service.DocumentIngestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 统一文档接入 API 控制器
 */
@RestController
@RequestMapping("/api/documents/ingest")
public class DocumentIngestionController {

    @Autowired
    private DocumentIngestionService documentIngestionService;

    /**
     * 处理标准前端表单多文件 HTTP 直传
     */
    @PostMapping("/http")
    public ResponseEntity<List<DocumentMetadata>> ingestFromHttp(@RequestParam("files") MultipartFile[] files) throws Exception {
        List<DocumentMetadata> result = documentIngestionService.ingestFromHttp(files);
        return ResponseEntity.ok(result);
    }

    /**
     * 处理前端提交的 URL 资源直链下载摄入
     */
    @PostMapping("/url")
    public ResponseEntity<DocumentMetadata> ingestFromUrl(@RequestParam("url") String url) throws Exception {
        DocumentMetadata result = documentIngestionService.ingestFromUrl(url);
        return ResponseEntity.ok(result);
    }

    /**
     * 处理服务端执行本地目录扫描获取数据操作
     */
    @PostMapping("/local")
    public ResponseEntity<List<DocumentMetadata>> ingestFromLocal(@RequestParam("directoryPath") String directoryPath) throws Exception {
        List<DocumentMetadata> result = documentIngestionService.ingestFromLocalDirectory(directoryPath);
        return ResponseEntity.ok(result);
    }

    /**
     * 处理 SFTP 远程抓取配置请求
     */
    @PostMapping("/sftp")
    public ResponseEntity<List<DocumentMetadata>> ingestFromSftp(
            @RequestParam("host") String host,
            @RequestParam("port") int port,
            @RequestParam("user") String user,
            @RequestParam("password") String password,
            @RequestParam("remoteDir") String remoteDir) throws Exception {
        List<DocumentMetadata> result = documentIngestionService.ingestFromSftp(host, port, user, password, remoteDir);
        return ResponseEntity.ok(result);
    }
}
