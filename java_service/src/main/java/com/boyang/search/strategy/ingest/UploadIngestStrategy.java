package com.boyang.search.strategy.ingest;

import com.boyang.search.entity.SysDocBatch;
import com.boyang.search.model.DocIngestRequest;
import com.boyang.search.utils.FileTypeValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 上传入库策略（页面上传 / 接口上传）。
 * <p>
 * [统一化] 子类职责收敛为「文件来源」：仅做大小/类型校验，提供 byte[]，
 * 其余（sanitize 命名 / full_hash 内容标识 / 前置去重 / 分级 store / full_hash 透传）
 * 全部复用 {@link AbstractIngestStrategy#processFile} 公共管线。
 */
@Component
public class UploadIngestStrategy extends AbstractIngestStrategy {

    private static final Logger log = LoggerFactory.getLogger(UploadIngestStrategy.class);

    @Value("${doc.upload.max-file-size:104857600}")
    private long maxFileSizeBytes;
    @Override
    public String getStrategyType() {
        return "UPLOAD";
    }

    /**
     * 执行文件上传的导入流程。
     * 业务功能：遍历上传的多文件，将流直接交给基类公共处理管线，收集生成的任务信息列表。
     * 关键流程：
     *   1. 校验入参 uploadFiles 是否为空；
     *   2. 遍历每个 MultipartFile；
     *   3. 调用基类 processFile 方法，直接以 file::getInputStream 传入流，交由基类统一实现大小限制、魔数检测、去重、存储及任务信息组装；
     *   4. 将成功生成的任务信息加入结果集返回。
     *
     * @param req   入库请求DTO
     * @param batch 导入批次日志记录实体
     * @return 派发任务的信息映射列表
     */
    @Override
    public List<Map<String, String>> process(DocIngestRequest req, SysDocBatch batch) throws Exception {
        List<Map<String, String>> result = new ArrayList<>();
        MultipartFile[] files = req.getUploadFiles();
        if (files == null || files.length == 0) {
            return result;
        }

        for (MultipartFile file : files) {
            // 核心流程：直接通过 file::getInputStream 传递流，由基类进行大小校验、魔数判断、Hash去重与分级存储
            // 这种设计遵循防线上移原则，精简了子类的重复代码，且避免了提前加载全部字节造成的额外内存开销
            Map<String, String> info = processFile(
                    file::getInputStream,
                    file.getOriginalFilename(), req, batch);
            if (info != null) {
                result.add(info);
            }
        }
        return result;
    }
}
