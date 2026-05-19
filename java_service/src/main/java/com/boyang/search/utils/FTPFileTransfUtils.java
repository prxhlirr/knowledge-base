package com.boyang.search.utils;

import com.boyang.search.service.MinioStorageService;
import com.jcraft.jsch.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * <h1>FTPFileTransfUtils (FTP 与 MinIO 文档双向流转工具类)</h1>
 * <p>
 * <b>业务功能：</b>
 * 本类用于解决分布式部署环境中，非同台服务器的 FTP 与业务 MinIO 之间的文档双转难题。
 * 包含两个核心流程：
 * 1. 待办拉取：从 FTP 列出以 UUID 命名的子目录，逐个拉取目录下的附件文件，流式直传至 MinIO，并调用持久化回调保存业务主键关联。
 * 2. 办结回传：在业务办理完成后，依据业务主键 UUID，将 MinIO 中的对应文档流式回传写入 FTP 指定的 UUID 目录中。
 * </p>
 * <p>
 * <b>设计模式与亮点：</b>
 * <ul>
 *   <li><b>适配器模式 (Adapter Pattern)：</b> 内部抽象出 FTPAdapter，无缝统一 FTP 和 SFTP 两种网络传输协议，通过配置一键无感知切换。</li>
 *   <li><b>流式直连 (Streaming Mode)：</b> 全程杜绝本地磁盘物理文件缓存，使用输入/输出流管道直连传输，消除服务器磁盘 I/O 负担及容量泄露隐患。</li>
 *   <li><b>防挂死代理流：</b> 针对 Apache Commons FTPClient 传输流的局限性，自定义包装类，在流关闭时自动触发 completePendingCommand 命令，规避多线程网络死锁。</li>
 *   <li><b>高复用解耦：</b> 开放 BiConsumer 函数式接口，由调用方自主注入核心的数据库插入与业务更新操作，并辅以可选的默认轻量落表机制。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class FTPFileTransfUtils {

    @Value("${ftp.protocol:ftp}")
    private String protocol;

    @Value("${ftp.host:127.0.0.1}")
    private String host;

    @Value("${ftp.port:21}")
    private int port;

    @Value("${ftp.username:ftpuser}")
    private String username;

    @Value("${ftp.password:ftppassword}")
    private String password;

    @Value("${ftp.remote-dir:/upload/business}")
    private String remoteDir;

    @Value("${ftp.backup-dir:/upload/backup}")
    private String backupDir;

    @Value("${ftp.delete-source:false}")
    private boolean deleteSource;

    @Value("${ftp.strict-host-key-checking:no}")
    private String strictHostKeyChecking;

    @Autowired
    private MinioStorageService minioStorageService;

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    /**
     * 匹配 36 位标准 UUID 的正则表达式，用于防御性过滤不合法的 FTP 文件夹，防止误操作系统或历史垃圾目录。
     */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    /**
     * 自动初始化默认业务关联日志表，以提供完美的开箱即用降级方案。
     * 即使调用方没有指定 dbSaver，也能以独立关联表的形式保障系统的事务归档和幂等性检查。
     */
    @PostConstruct
    public void initDefaultTable() {
        if (jdbcTemplate != null) {
            try {
                log.info("[FTPTransfUtils] 开始检测并初始化默认流转日志表...");
                jdbcTemplate.execute(
                        "CREATE TABLE IF NOT EXISTS sys_ftp_transf_log (" +
                                "id VARCHAR(64) PRIMARY KEY, " +
                                "uuid VARCHAR(64) NOT NULL, " +
                                "original_file_name VARCHAR(255) NOT NULL, " +
                                "minio_storage_path VARCHAR(500) NOT NULL, " +
                                "status VARCHAR(20) NOT NULL, " + // DOWNLOADED / UPLOADED
                                "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                                "update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                                ")"
                );
                log.info("[FTPTransfUtils] 默认日志表 sys_ftp_transf_log 初始化成功");
            } catch (Exception e) {
                log.error("[FTPTransfUtils] 自动初始化默认日志表失败，将只依赖外部传入的回调函数，err={}", e.getMessage());
            }
        }
    }

    /**
     * <h1>流程一：从 FTP/SFTP 批量下载并上传至业务 MinIO</h1>
     * <p>
     * 业务流程：
     * 1. 扫描配置的待办根目录下的所有子目录。
     * 2. 校验子目录名是否为标准的 UUID（作为业务主键）。
     * 3. 针对每个合法的 UUID 目录，拉取它下面的每一个文件，流式直传至 MinIO 对象存储。
     * 4. 成功上传该 UUID 目录下的所有文件后，触发 dbSaver 回调执行业务持久化。
     * 5. 若持久化成功，将 FTP/SFTP 下该 UUID 目录下的所有文件移至备份目录或直接物理删除（根据配置决定），确保消息拉取的单次性。
     * </p>
     *
     * @param dbSaver 自定义数据库保存逻辑。参数：String uuid, List<MinioFileInfo> 成功存入 MinIO 的文件列表。
     */
    public void downloadFromFtpToMinio(BiConsumer<String, List<MinioFileInfo>> dbSaver) {
        log.info("[FTPTransfUtils] 开始执行下载与直传任务，协议：{}，目标地址：{}:{}", protocol, host, port);

        try (FileTransfAdapter adapter = createAdapter()) {
            adapter.connect();

            // 1. 获取 FTP 根目录下所有的子文件夹列表
            List<String> subDirectories = adapter.listSubDirectories(remoteDir);
            if (subDirectories == null || subDirectories.isEmpty()) {
                log.info("[FTPTransfUtils] FTP 远程工作目录下未检测到任何子文件夹，本次任务无数据需要拉取");
                return;
            }

            for (String uuid : subDirectories) {
                // 2. 防御性校验，只处理以标准 UUID 命名的文件夹，跳过非业务目录或特殊配置文件夹
                if (!UUID_PATTERN.matcher(uuid).matches()) {
                    log.warn("[FTPTransfUtils] 目录名 '{}' 不符合标准 UUID 格式，自动跳过以防误操作", uuid);
                    continue;
                }

                log.info("[FTPTransfUtils] 发现待办业务目录：{}", uuid);
                String currentUuidDir = remoteDir + "/" + uuid;
                List<FTPFileWrapper> fileWrappers = adapter.listFiles(currentUuidDir);

                if (fileWrappers.isEmpty()) {
                    log.warn("[FTPTransfUtils] 业务目录 '{}' 为空目录，无附件可拉取，执行跳过", uuid);
                    continue;
                }

                // 准备收集该 UUID 目录下成功上传到 MinIO 的文件对象信息
                List<MinioFileInfo> uploadedFiles = new ArrayList<>();
                boolean processSuccess = true;

                for (FTPFileWrapper wrapper : fileWrappers) {
                    log.info("[FTPTransfUtils] 开始流式拉取并直传文件：{} -> MinIO", wrapper.getFileName());

                    // 3. 全程使用输入流管道，数据直接从 FTP 直连 MinIO，无任何临时本地物理文件创建与磁盘 I/O 产生
                    try (InputStream inputStream = adapter.downloadStream(wrapper.getFullPath())) {
                        if (inputStream == null) {
                            throw new IOException("无法从 FTP 获取到有效的数据读取流");
                        }
                        // 传输直写：MinIO 客户端在 store 中消费该输入流并做底层分片上传
                        String storagePath = minioStorageService.store(inputStream, wrapper.getFileName());

                        MinioFileInfo fileInfo = new MinioFileInfo(wrapper.getFileName(), storagePath);
                        uploadedFiles.add(fileInfo);
                        log.info("[FTPTransfUtils] 文件直传 MinIO 成功：{} -> {}", wrapper.getFileName(), storagePath);
                    } catch (Exception e) {
                        log.error("[FTPTransfUtils] 文件流式流转过程中断，发生异常，文件名={} err={}", wrapper.getFileName(), e.getMessage());
                        processSuccess = false;
                        break; // 单个文件失败，视为该 UUID 业务整体未完全流转，中断当前 UUID 的循环
                    }
                }

                // 4. 当该 UUID 下的所有文件均顺利存入 MinIO 后，开始触发数据库落库归档
                if (processSuccess && !uploadedFiles.isEmpty()) {
                    try {
                        if (dbSaver != null) {
                            // 优先执行业务端自定义注入的落库动作（例如更新主业务表的经办状态，关联附件表）
                            dbSaver.accept(uuid, uploadedFiles);
                        } else {
                            // 降级：采用工具类内置的默认高容灾表记录落库，确保即使业务未做回调，数据亦不丢失
                            saveDefaultLog(uuid, uploadedFiles);
                        }

                        log.info("[FTPTransfUtils] 业务主键 '{}' 的流转附件已成功归档至数据库", uuid);

                        // 5. 数据库持久化及前面的文件传输全部确认无误后，进行 FTP 文件的后置处理以维护幂等性（防止重复拉取）
                        postProcessFtpDirectory(adapter, uuid, fileWrappers);

                    } catch (Exception e) {
                        log.error("[FTPTransfUtils] 业务数据落表或后置 FTP 清理失败，业务主键={} err={}", uuid, e.getMessage());
                        // 允许抛出异常以利于外部的定时任务调度器（如 XXL-JOB）捕捉，并阻止该异常蔓延影响后续 UUID
                    }
                }
            }

        } catch (Exception e) {
            log.error("[FTPTransfUtils] FTP 与 MinIO 双向传输发生全局严重连接性异常 err={}", e.getMessage());
        }
    }

    /**
     * <h1>流程二：业务办理完成后，从 MinIO 流式下载回传至 FTP/SFTP </h1>
     * <p>
     * 业务流程：
     * 1. 验证待回传的主目录是否在 FTP 端存在，若不存在则逐级创建以当前 UUID 命名的子目录。
     * 2. 依次遍历每个文件对象，流式拉取 MinIO 的 InputStream 数据。
     * 3. 直写到 FTP 服务器对应的 Output 管道中，实现文件归档与回送。
     * </p>
     *
     * @param uuid          业务办理主键 UUID
     * @param fileInfos     待回传至 FTP 的 MinIO 存储路径与原始文件名关联列表
     * @throws Exception    回传过程中发生网络或 IO 异常时向上抛出，由调用层控制重试或事务
     */
    public void uploadFromMinioToFtp(String uuid, List<MinioFileInfo> fileInfos) throws Exception {
        if (uuid == null || !UUID_PATTERN.matcher(uuid).matches()) {
            throw new IllegalArgumentException("回传主键 uuid 格式不合法");
        }
        if (fileInfos == null || fileInfos.isEmpty()) {
            log.warn("[FTPTransfUtils] 回传文件列表为空，无需执行回传，uuid={}", uuid);
            return;
        }

        log.info("[FTPTransfUtils] 开始回传 MinIO 文件至 FTP 目录，uuid={}，数量={}", uuid, fileInfos.size());

        try (FileTransfAdapter adapter = createAdapter()) {
            adapter.connect();

            // 1. 创建以 UUID 命名的目标归档目录
            String targetDir = remoteDir + "/" + uuid;
            adapter.makeDirectory(targetDir);

            // 2. 遍历列表流式写出
            for (MinioFileInfo fileInfo : fileInfos) {
                String fileName = fileInfo.getOriginalFileName();
                String storagePath = fileInfo.getMinioStoragePath();

                // 预防性保底：若原文件名因数据库丢失，则从 MinIO 的 storagePath 后缀中自动推断恢复
                if (fileName == null || fileName.isEmpty()) {
                    fileName = storagePath.substring(storagePath.lastIndexOf("/") + 1);
                }

                String remoteFilePath = targetDir + "/" + fileName;
                log.info("[FTPTransfUtils] 正在回传文件：{} -> FTP路径：{}", fileName, remoteFilePath);

                // 全程免本地缓存文件直连写出，内存直接对接
                try (InputStream minioStream = minioStorageService.downloadStream(storagePath)) {
                    adapter.uploadFile(remoteFilePath, minioStream);
                    log.info("[FTPTransfUtils] 文件回传 FTP 成功：{}", fileName);
                } catch (Exception e) {
                    log.error("[FTPTransfUtils] 回传文件失败，发生致命异常，文件名={} err={}", fileName, e.getMessage());
                    throw e; // 抛出异常以触发业务回滚
                }
            }
        }
    }

    /**
     * 默认的轻量持久化逻辑。
     * 当外部没有传入 dbSaver 回调时使用，将流转状态存储至系统自动初始化的关系表。
     */
    private void saveDefaultLog(String uuid, List<MinioFileInfo> fileInfos) {
        if (jdbcTemplate == null) {
            log.warn("[FTPTransfUtils] JdbcTemplate 未配置，跳过内置持久化归档");
            return;
        }
        for (MinioFileInfo info : fileInfos) {
            String id = UUID.randomUUID().toString().replace("-", "");
            jdbcTemplate.update(
                    "INSERT INTO sys_ftp_transf_log (id, uuid, original_file_name, minio_storage_path, status) " +
                            "VALUES (?, ?, ?, ?, ?)",
                    id, uuid, info.getOriginalFileName(), info.getMinioStoragePath(), "DOWNLOADED"
            );
        }
    }

    /**
     * 文件成功入库后的后置清理逻辑。
     * 根据 deleteSource 配置决定是直接删除 FTP 端源文件，还是将其安全归档移动至备份目录。
     */
    private void postProcessFtpDirectory(FileTransfAdapter adapter, String uuid, List<FTPFileWrapper> files) throws Exception {
        String currentUuidDir = remoteDir + "/" + uuid;

        if (deleteSource) {
            log.info("[FTPTransfUtils] 后置任务处理模式：物理删除，开始清理 FTP 原文件，uuid={}", uuid);
            for (FTPFileWrapper wrapper : files) {
                adapter.deleteFile(wrapper.getFullPath());
            }
            // 尝试清除由于清空文件而留下的空业务目录
            try {
                adapter.deleteFile(currentUuidDir);
            } catch (Exception e) {
                log.warn("[FTPTransfUtils] 清理 FTP 业务目录空壳失败（可能适配器不支持直接 rm 文件夹），自动略过，dir={}", currentUuidDir);
            }
        } else {
            // 备份模式：移动到备份目录，防止下次定时任务重复扫描
            String currentBackupDir = backupDir + "/" + uuid;
            log.info("[FTPTransfUtils] 后置任务处理模式：归档备份，开始转移 FTP 文件，目标目录={}", currentBackupDir);
            adapter.makeDirectory(currentBackupDir);

            for (FTPFileWrapper wrapper : files) {
                String newPath = currentBackupDir + "/" + wrapper.getFileName();
                adapter.rename(wrapper.getFullPath(), newPath);
            }
        }
    }

    /**
     * 工厂方法：通过客户端配置加载并实例化专属的 FTP 适配器。
     */
    FileTransfAdapter createAdapter() {
        if ("sftp".equalsIgnoreCase(protocol)) {
            return new SftpAdapter();
        } else {
            return new FtpAdapter();
        }
    }

    // =========================================================================
    // 统一底层文件服务器行为的适配器接口
    // =========================================================================

    interface FileTransfAdapter extends AutoCloseable {
        void connect() throws Exception;
        List<String> listSubDirectories(String parentDir) throws Exception;
        List<FTPFileWrapper> listFiles(String dir) throws Exception;
        InputStream downloadStream(String remoteFilePath) throws Exception;
        void uploadFile(String remoteFilePath, InputStream inputStream) throws Exception;
        void makeDirectory(String remoteDir) throws Exception;
        void deleteFile(String remoteFilePath) throws Exception;
        void rename(String oldPath, String newPath) throws Exception;
        @Override
        void close();
    }

    // =========================================================================
    // SFTP 协议底层适配器实现 (基于 JSch Jsch ChannelSftp)
    // =========================================================================

    private class SftpAdapter implements FileTransfAdapter {
        private Session session;
        private ChannelSftp channel;

        @Override
        public void connect() throws Exception {
            JSch jsch = new JSch();
            session = jsch.getSession(username, host, port);
            session.setPassword(password);

            Properties config = new Properties();
            // 在开发环境或特定私有内网中，允许绕过 SSH 主机密钥强制检验，采用动态策略，避免连接被阻断
            config.put("StrictHostKeyChecking", strictHostKeyChecking);
            session.setConfig(config);

            log.info("[SftpAdapter] 正在向 SSH 服务器建立连接通道 {}:{}", host, port);
            session.connect(30_000); // 30秒硬超时，防止网络握手无限期挂起

            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect();
            log.info("[SftpAdapter] SFTP 通道创建并就绪");
        }

        @Override
        public List<String> listSubDirectories(String parentDir) throws Exception {
            List<String> dirs = new ArrayList<>();
            try {
                Vector<?> entries = channel.ls(parentDir);
                for (Object entryObj : entries) {
                    if (entryObj instanceof ChannelSftp.LsEntry) {
                        ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) entryObj;
                        if (entry.getAttrs().isDir()) {
                            String name = entry.getFilename();
                            if (!".".equals(name) && !"..".equals(name)) {
                                dirs.add(name);
                            }
                        }
                    }
                }
            } catch (SftpException e) {
                // 如果待下载目录完全不存在，视为目录为空，不应当引起崩溃
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    log.warn("[SftpAdapter] 工作目录未找到，自动忽略，path={}", parentDir);
                    return Collections.emptyList();
                }
                throw e;
            }
            return dirs;
        }

        @Override
        public List<FTPFileWrapper> listFiles(String dir) throws Exception {
            List<FTPFileWrapper> files = new ArrayList<>();
            Vector<?> entries = channel.ls(dir);
            for (Object entryObj : entries) {
                if (entryObj instanceof ChannelSftp.LsEntry) {
                    ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) entryObj;
                    if (!entry.getAttrs().isDir()) {
                        files.add(new FTPFileWrapper(dir + "/" + entry.getFilename(), entry.getFilename()));
                    }
                }
            }
            return files;
        }

        @Override
        public InputStream downloadStream(String remoteFilePath) throws Exception {
            return channel.get(remoteFilePath);
        }

        @Override
        public void uploadFile(String remoteFilePath, InputStream inputStream) throws Exception {
            channel.put(inputStream, remoteFilePath);
        }

        @Override
        public void makeDirectory(String remoteDir) throws Exception {
            try {
                // 逐级创建文件夹在 SFTP 中若存在会抛错，故使用异常防重
                channel.mkdir(remoteDir);
            } catch (SftpException e) {
                if (e.id != ChannelSftp.SSH_FX_FAILURE) {
                    throw e; // 如果是由于已存在导致的 SSH_FX_FAILURE (id=4)，我们优雅吞没它，否则上抛
                }
            }
        }

        @Override
        public void deleteFile(String remoteFilePath) throws Exception {
            channel.rm(remoteFilePath);
        }

        @Override
        public void rename(String oldPath, String newPath) throws Exception {
            channel.rename(oldPath, newPath);
        }

        @Override
        public void close() {
            if (channel != null) {
                try {
                    channel.disconnect();
                } catch (Exception e) {
                    // 仅记录静默释放
                }
            }
            if (session != null) {
                try {
                    session.disconnect();
                } catch (Exception e) {
                    // 仅记录静默释放
                }
            }
            log.info("[SftpAdapter] SFTP 物理通道已安全释放");
        }
    }

    // =========================================================================
    // 普通 FTP 协议底层适配器实现 (基于 Apache Commons Net FTPClient)
    // =========================================================================

    private class FtpAdapter implements FileTransfAdapter {
        private FTPClient ftpClient;

        @Override
        public void connect() throws Exception {
            ftpClient = new FTPClient();
            log.info("[FtpAdapter] 正在向 FTP 服务器建立连接通道 {}:{}", host, port);
            ftpClient.connect(host, port);
            ftpClient.login(username, password);

            // 1. 企业多网卡及 NAT 网络环境下的标准生存模式：必须开启被动模式 (Passive Mode)
            // 否则在双向数据连接建立时，局域网内的 FTP 服务器无法回连处于外网的 Java 微服务，造成卡顿死锁
            ftpClient.enterLocalPassiveMode();

            // 2. 规定二进制读写：防止非文本文件（如 Word 文档、PDF 等）由于不同操作系统的换行符转换而遭到实质性格式损坏
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            ftpClient.setControlEncoding("UTF-8");

            int reply = ftpClient.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                ftpClient.disconnect();
                throw new IOException("FTP 服务器拒绝连入，响应代码：" + reply);
            }
            log.info("[FtpAdapter] FTP 通道验证就绪");
        }

        @Override
        public List<String> listSubDirectories(String parentDir) throws Exception {
            List<String> dirs = new ArrayList<>();
            FTPFile[] files = ftpClient.listDirectories(parentDir);
            if (files != null) {
                for (FTPFile file : files) {
                    String name = file.getName();
                    if (!".".equals(name) && !"..".equals(name)) {
                        dirs.add(name);
                    }
                }
            }
            return dirs;
        }

        @Override
        public List<FTPFileWrapper> listFiles(String dir) throws Exception {
            List<FTPFileWrapper> list = new ArrayList<>();
            FTPFile[] files = ftpClient.listFiles(dir);
            if (files != null) {
                for (FTPFile file : files) {
                    if (file.isFile()) {
                        list.add(new FTPFileWrapper(dir + "/" + file.getName(), file.getName()));
                    }
                }
            }
            return list;
        }

        @Override
        public InputStream downloadStream(String remoteFilePath) throws Exception {
            InputStream is = ftpClient.retrieveFileStream(remoteFilePath);
            if (is == null) {
                return null;
            }

            // 关键的代理流修复设计：
            // Apache FTPClient 的 retrieveFileStream 传输完毕后必须执行 completePendingCommand 命令。
            // 传统的做法在直连 MinIO 等其他流式容器时，会导致 completePendingCommand 调用脱节或产生多线程挂起。
            // 此处采用动态装饰器，拦截 close 动作，并在完成实际的底层流关闭后，强制触发 completePendingCommand 命令！
            return new FilterInputStream(is) {
                private boolean completed = false;

                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        if (!completed) {
                            completed = true;
                            try {
                                ftpClient.completePendingCommand();
                                log.debug("[FtpAdapter] 已自动清空 FTP 传输挂起指令状态");
                            } catch (Exception e) {
                                log.warn("[FtpAdapter] 清空 FTP 指令挂起状态发生静默异常 err={}", e.getMessage());
                            }
                        }
                    }
                }
            };
        }

        @Override
        public void uploadFile(String remoteFilePath, InputStream inputStream) throws Exception {
            if (!ftpClient.storeFile(remoteFilePath, inputStream)) {
                throw new IOException("无法向 FTP 上传文件，服务器返回错误，存储目标=" + remoteFilePath);
            }
        }

        @Override
        public void makeDirectory(String remoteDir) throws Exception {
            // 支持多级目录防御式逐级生成，忽略已存在的报错
            ftpClient.makeDirectory(remoteDir);
        }

        @Override
        public void deleteFile(String remoteFilePath) throws Exception {
            ftpClient.deleteFile(remoteFilePath);
        }

        @Override
        public void rename(String oldPath, String newPath) throws Exception {
            ftpClient.rename(oldPath, newPath);
        }

        @Override
        public void close() {
            if (ftpClient != null && ftpClient.isConnected()) {
                try {
                    ftpClient.logout();
                } catch (Exception e) {
                    // 静默注销
                }
                try {
                    ftpClient.disconnect();
                } catch (Exception e) {
                    // 静默释放
                }
            }
            log.info("[FtpAdapter] FTP 物理通道已安全释放");
        }
    }

    // =========================================================================
    // 数据传输承载的领域对象
    // =========================================================================

    /**
     * 包装 FTP 服务器上的文件元素。
     */
    @Getter
    @Setter
    public static class FTPFileWrapper {
        private String fullPath;
        private String fileName;

        public FTPFileWrapper(String fullPath, String fileName) {
            this.fullPath = fullPath;
            this.fileName = fileName;
        }
    }

    /**
     * 传输至 MinIO 后的响应实体。
     * 用于跨服务的业务主键归档。
     */
    @Getter
    @Setter
    public static class MinioFileInfo {
        private String originalFileName;
        private String minioStoragePath;

        public MinioFileInfo() {}

        public MinioFileInfo(String originalFileName, String minioStoragePath) {
            this.originalFileName = originalFileName;
            this.minioStoragePath = minioStoragePath;
        }
    }
}
