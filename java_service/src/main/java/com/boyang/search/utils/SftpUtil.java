package com.boyang.search.utils;

import com.jcraft.jsch.*;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Vector;
import java.util.Properties;

/**
 * SSH/SFTP 处理工具类
 */
public class SftpUtil implements AutoCloseable {
    private Session session;
    private ChannelSftp channel;

    /**
     * 使用密码认证创建 SFTP 连接。
     * [A-2 修复] 移除 StrictHostKeyChecking=no，改用 known_hosts 文件校验主机指纹，
     *            防止 MITM 中间人攻击。若 knownHostsPath 为 null，则使用系统默认 known_hosts。
     *
     * @param host           SFTP 主机地址
     * @param port           SFTP 端口（通常 22）
     * @param user           用户名
     * @param password       密码
     * @param knownHostsPath already-known_hosts 文件路径（null 则使用系统默认 ~/.ssh/known_hosts）
     */
    public SftpUtil(String host, int port, String user, String password,
                    String knownHostsPath) throws JSchException {
        JSch jsch = new JSch();
        // [A-2 修复] 使用 known_hosts 校验，若文件不存在则创建空文件（禁止 no-check）
        if (knownHostsPath != null && !knownHostsPath.isEmpty()) {
            jsch.setKnownHosts(knownHostsPath);
        } else {
            // 默认使用用户主目录的 known_hosts
            String defaultKH = System.getProperty("user.home") + "/.ssh/known_hosts";
            java.io.File khFile = new java.io.File(defaultKH);
            if (khFile.exists()) {
                jsch.setKnownHosts(defaultKH);
            }
            // 若 known_hosts 不存在，JSch 默认行为是拒绝未知主机（StrictHostKeyChecking 默认 ask）
        }
        session = jsch.getSession(user, host, port);
        session.setPassword(password);
        // [A-2 修复] 不再禁用主机密钥校验，若已知主机未注册则连接失败（安全拒绝）
        // 注意：首次连接新主机需手动将其指纹加入 known_hosts
        session.connect(30_000); // 30s 连接超时
        channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect();
    }

    /**
     * 兼容旧调用方的简化构造方法（不传 knownHostsPath，使用系统默认）。
     */
    public SftpUtil(String host, int port, String user, String password) throws JSchException {
        this(host, port, user, password, null);
    }

    public List<SftpFile> listFiles(String path) throws SftpException {
        List<SftpFile> files = new ArrayList<>();
        Vector<ChannelSftp.LsEntry> entries = channel.ls(path);
        for (ChannelSftp.LsEntry entry : entries) {
            if (entry.getAttrs().isDir()) {
                if (!".".equals(entry.getFilename()) && !"..".equals(entry.getFilename())) {
                    files.addAll(listFiles(path + "/" + entry.getFilename()));
                }
            } else {
                String name = entry.getFilename().toLowerCase();
                if (name.endsWith(".txt") || name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx")) {
                    files.add(new SftpFile(path + "/" + entry.getFilename(), entry.getFilename()));
                }
            }
        }
        return files;
    }

    public InputStream downloadStream(String filePath) throws SftpException {
        return channel.get(filePath);
    }

    @Override
    public void close() {
        if (channel != null) channel.disconnect();
        if (session != null) session.disconnect();
    }

    public static class SftpFile {
        public String fullPath;
        public String name;
        public SftpFile(String fullPath, String name) {
            this.fullPath = fullPath;
            this.name = name;
        }
    }
}
