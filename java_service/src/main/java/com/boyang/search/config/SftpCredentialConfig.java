package com.boyang.search.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SFTP 凭据配置类（A-4 修复）。
 * 业务功能：将 SFTP 服务器连接凭据从接口请求体（明文传输）迁移到配置文件，
 *           外部调用方仅传 credentialId，凭据由运维从 application.yml / 环境变量注入。
 * 安全原因：接口传明文密码会被日志记录、网络嗅探、访问日志泄露；
 *           配置文件方式仅 JVM 进程持有，不经网络传输。
 */
@Data
@Component
@ConfigurationProperties(prefix = "sftp")
public class SftpCredentialConfig {

    /** known_hosts 文件路径，用于 SFTP 主机指纹校验（A-2 修复配套） */
    private String knownHostsPath = "~/.ssh/known_hosts";

    /**
     * 预配置的 SFTP 凭据池，key = credentialId（如 fileserver-prod）。
     * 配置示例：
     * <pre>
     * sftp:
     *   credentials:
     *     fileserver-prod:
     *       host: file-server.internal
     *       port: 22
     *       user: sftp-reader
     *       password: ${SFTP_FS_PASSWORD}
     * </pre>
     */
    private Map<String, SftpCredential> credentials;

    /**
     * 按 credentialId 获取凭据，不存在则抛出异常（拒绝连接到未预配置的服务器）。
     *
     * @param credentialId 凭据 ID，对应 sftp.credentials 下的 key
     * @return 对应凭据对象
     */
    public SftpCredential getCredential(String credentialId) {
        if (credentials == null || !credentials.containsKey(credentialId)) {
            throw new IllegalArgumentException(
                "未知的 SFTP 凭据 ID: " + credentialId +
                "，请在 sftp.credentials 中预先配置（已配置的 ID: " +
                (credentials != null ? credentials.keySet() : "无") + "）");
        }
        return credentials.get(credentialId);
    }

    /** 单个 SFTP 服务器凭据 */
    @Data
    public static class SftpCredential {
        private String host;
        private int    port = 22;
        private String user;
        private String password;
    }
}
