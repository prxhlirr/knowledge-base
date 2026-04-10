package com.boyang.search.strategy.ingest;

import com.boyang.search.model.DocIngestRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 抽象的入库策略类
 * 抽离各个子策略组装任务信息的公共逻辑，进一步实现代码解耦与复用。
 */
public abstract class AbstractIngestStrategy implements IngestStrategy {

    /**
     * 将存储好的文档实体构建成需要派发给后续流程的参数集合
     */
    protected Map<String, String> buildTaskInfo(String savedPath, String fileName, DocIngestRequest req, String contentHash) {
        HashMap<String, String> info = new HashMap<>();
        info.put("path", savedPath);
        info.put("name", fileName);
        info.put("targetIndex", req.getTargetIndex() != null ? req.getTargetIndex() : "kb_document_v1");
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
        return info;
    }

    protected static String nvl(String s) {
        return s != null ? s : "";
    }
}
