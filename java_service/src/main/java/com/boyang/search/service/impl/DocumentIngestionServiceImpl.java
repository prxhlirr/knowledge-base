package com.boyang.search.service.impl;

import com.boyang.search.entity.DocumentMetadata;
import com.boyang.search.service.DocumentIngestionService;
import com.boyang.search.service.KafkaProducerService;
import com.boyang.search.service.MinioStorageService;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class DocumentIngestionServiceImpl implements DocumentIngestionService {

    @Autowired
    private MinioStorageService minioStorageService;

//    @Autowired
//    private KafkaProducerService kafkaProducerService;

    @Override
    public List<DocumentMetadata> ingestFromHttp(MultipartFile[] files) throws Exception {
        List<DocumentMetadata> result = new ArrayList<>();
        for (MultipartFile file : files) {
            String originalFilename = file.getOriginalFilename();
            String contentType = file.getContentType();
            long size = file.getSize();
            try (InputStream is = file.getInputStream()) {
                String objectName = minioStorageService.store(is, originalFilename);
                DocumentMetadata metadata = DocumentMetadata.builder()
                        .originalFilename(originalFilename)
                        .objectName(objectName)
                        .source("HTTP")
                        .size(size)
                        .ext(getExt(originalFilename))
                        .build();
                result.add(metadata);
                // kafkaProducerService.sendDocIngestionEvent(metadata);
            }
        }
        return result;
    }

    @Override
    public DocumentMetadata ingestFromUrl(String url) throws Exception {
        URL downloadUrl = new URL(url);
        String path = downloadUrl.getPath();
        String originalFilename = path.substring(path.lastIndexOf('/') + 1);
        if (originalFilename.isEmpty()) {
            originalFilename = "downloaded_file";
        }
        
        try (InputStream is = downloadUrl.openStream()) {
            String objectName = minioStorageService.store(is, originalFilename);
            DocumentMetadata metadata = DocumentMetadata.builder()
                    .originalFilename(originalFilename)
                    .objectName(objectName)
                    .source("URL")
                    .size(0L) // 流式无法直接获知大小，或通过 Header Content-Length 获知
                    .originUrl(url)
                    .ext(getExt(originalFilename))
                    .build();
            // kafkaProducerService.sendDocIngestionEvent(metadata);
            return metadata;
        }
    }

    @Override
    public List<DocumentMetadata> ingestFromLocalDirectory(String directoryPath) throws Exception {
        List<DocumentMetadata> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(Paths.get(directoryPath))) {
             List<Path> filePaths = paths.filter(Files::isRegularFile).collect(Collectors.toList());
             for (Path file : filePaths) {
                 String originalFilename = file.getFileName().toString();
                 long size = Files.size(file);
                 try (InputStream is = Files.newInputStream(file)) {
                     String objectName = minioStorageService.store(is, originalFilename);
                     DocumentMetadata metadata = DocumentMetadata.builder()
                             .originalFilename(originalFilename)
                             .objectName(objectName)
                             .source("LOCAL")
                             .size(size)
                             .originUrl(file.toAbsolutePath().toString())
                             .ext(getExt(originalFilename))
                             .build();
                     result.add(metadata);
                     // kafkaProducerService.sendDocIngestionEvent(metadata);
                 }
             }
        }
        return result;
    }

    @Override
    public List<DocumentMetadata> ingestFromSftp(String host, int port, String user, String password, String remoteDir) throws Exception {
        List<DocumentMetadata> result = new ArrayList<>();
        JSch jsch = new JSch();
        // [A-2 修复] 移除 StrictHostKeyChecking=no，改用 known_hosts 校验主机指纹
        String defaultKH = System.getProperty("user.home") + "/.ssh/known_hosts";
        if (new java.io.File(defaultKH).exists()) {
            jsch.setKnownHosts(defaultKH);
        }
        Session session = jsch.getSession(user, host, port);
        session.setPassword(password);
        // 不设置 StrictHostKeyChecking=no，JSch 默认拥有严格校验行为
        session.connect(10000);

        ChannelSftp channelSftp = (ChannelSftp) session.openChannel("sftp");
        channelSftp.connect(10000);

        try {
            channelSftp.cd(remoteDir);
            @SuppressWarnings("unchecked")
            List<ChannelSftp.LsEntry> entries = channelSftp.ls(remoteDir);
            for (ChannelSftp.LsEntry entry : entries) {
                if (!entry.getAttrs().isDir()) {
                    String originalFilename = entry.getFilename();
                    long size = entry.getAttrs().getSize();
                    try (InputStream is = channelSftp.get(originalFilename)) {
                        String objectName = minioStorageService.store(is, originalFilename);
                        DocumentMetadata metadata = DocumentMetadata.builder()
                                .originalFilename(originalFilename)
                                .objectName(objectName)
                                .source("SFTP")
                                .size(size)
                                .originUrl("sftp://" + host + ":" + port + remoteDir + "/" + originalFilename)
                                .ext(getExt(originalFilename))
                                .build();
                        result.add(metadata);
                        // kafkaProducerService.sendDocIngestionEvent(metadata);
                    }
                }
            }
        } finally {
            channelSftp.disconnect();
            session.disconnect();
        }
        return result;
    }

    private String getExt(String originalFilename) {
        if (originalFilename != null && originalFilename.contains(".")) {
            return originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        return "";
    }
}
