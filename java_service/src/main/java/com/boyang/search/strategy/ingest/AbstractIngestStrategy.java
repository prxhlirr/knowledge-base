package com.boyang.search.strategy.ingest;

import com.boyang.search.model.DocIngestRequest;
import com.boyang.search.service.DocIndexRoutingService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;

/**
 * 抽象的入库策略类
 * 抽离各个子策略组装任务信息的公共逻辑，进一步实现代码解耦与复用。
 */
public abstract class AbstractIngestStrategy implements IngestStrategy {

    /**
     * [动态路由] 注入索引路由服务，根据文档 tag 决定写入哪个 ES 索引。
     * 根因：原代码直接读 req.getTargetIndex()（默认值 kb_document_v1），
     *       DocIndexRoutingService 从未被调用，所有文档都进了 kb_document_v1。
     * 修复：此处是所有入库策略（LOCAL/UPLOAD/SFTP/URL）的唯一公共出口，
     *       在此注入路由服务，统一解决所有入库路径的索引路由问题。
     */
    @Autowired
    private DocIndexRoutingService docIndexRoutingService;

    /**
     * 将存储好的文档实体构建成需要派发给后续流程的参数集合
     */
    protected Map<String, String> buildTaskInfo(String savedPath, String fileName, DocIngestRequest req, String contentHash) {
        HashMap<String, String> info = new HashMap<>();
        info.put("path", savedPath);
        info.put("name", fileName);

        // [动态路由核心修复] targetIndex 决策逻辑：
        //   1. 调用方显式传入了非默认索引名 → 尊重调用方意图，直接使用
        //   2. 否则（null 或默认值 kb_document_v1）→ 通过 tag 查路由表动态决定
        //      - tag 非空且有规则 → 路由到对应专属索引（如 kb_document_official）
        //      - tag 为空或无规则 → 走 fallback 索引（如 kb_document_official）
        String targetIndex = req.getTargetIndex();
        if (targetIndex == null || targetIndex.isEmpty() || "kb_document_v1".equals(targetIndex)) {
            targetIndex = docIndexRoutingService.route(req.getTag());
        }
        info.put("targetIndex", targetIndex);

        info.put("visibility", req.getVisibility() != null ? req.getVisibility() : "INTERNAL");
        info.put("deptCode", req.getDeptCode() != null ? req.getDeptCode() : "");
        info.put("tag", nvl(req.getTag()));
        info.put("unit", nvl(req.getUnit()));
        info.put("docNumber", nvl(req.getDocNumber()));
        info.put("owner", nvl(req.getOwner()));
        info.put("searchQueries", nvl(req.getSearchQueries()));
        info.put("publishTime", nvl(req.getPublishTime()));
        info.put("sourceSystem", nvl(req.getSourceSystem()));
        info.put("contentHash", contentHash != null ? contentHash : "");
        // [PDF扫描件] 跳页开关：Java boolean → "true"/"false" 字符串写入 taskInfo，
        // 由 DocIngestService 转换为整数 skip_pages 写入 Redis payload 传给 Python。
        info.put("skipFirstPage", req.isSkipFirstPage() ? "true" : "false");
        return info;
    }

    protected static String nvl(String s) {
        return s != null ? s : "";
    }
}
