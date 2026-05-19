package com.boyang.search.strategy.ingest;

import com.boyang.search.entity.SysDocBatch;
import com.boyang.search.model.DocIngestRequest;
import java.util.List;
import java.util.Map;

/**
 * 文档入库策略接口
 * 用于消除 DocIngestService 中的 switch-case，实现不同接入方式（SFTP, LOCAL, URL, UPLOAD）的解耦。
 */
public interface IngestStrategy {

    /**
     * 策略标识，如 "SFTP", "LOCAL", "URL", "UPLOAD"
     * @return 策略字符串标识
     */
    String getStrategyType();

    /**
     * 执行具体的拉取、上传和解析入库
     * @param request 入库请求（包含参数、元数据，如果为 UPLOAD 还会包含 uploadFiles 附件对象）
     * @param batch 当前的批次记录（如果拉取失败，策略可以在里面累加 errorCount）
     * @return 构建成功的解析文件信息列表（用于后续统一派发任务）
     * @throws Exception 处理异常
     */
    List<Map<String, String>> process(DocIngestRequest request, SysDocBatch batch) throws Exception;
}
