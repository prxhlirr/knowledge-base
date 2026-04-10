package com.boyang.search.controller;

import com.boyang.search.service.SimilarityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 向量相似度探针管理接口
 *
 * 业务功能：为后台管理页面（Similarity Lab）提供两个专属接口：
 *   1. POST /api/admin/similarity/compare      — 两段文本余弦相似度对比
 *   2. POST /api/admin/similarity/corpus-scan  — ES 全库文档批量相似度扫描
 *
 * 接口均无鉴权拦截（建议生产部署时接入内网 IP 白名单），专供内部可信管理员使用。
 */
@RestController
@RequestMapping("/api/v1/admin/similarity")
@CrossOrigin(origins = "*")
public class SimilarityController {

    @Autowired
    private SimilarityService similarityService;

    /**
     * 业务功能：计算两段输入文本在 BGE-M3 向量空间中的余弦相似度
     * 关键流程：提取 text_a / text_b → 参数校验 → SimilarityService#compareSimilarity
     *
     * 请求体示例：
     * <pre>
     * {
     *   "textA": "保密义务",
     *   "textB": "缄默要求"
     * }
     * </pre>
     *
     * 响应示例：
     * <pre>
     * {
     *   "code": 200,
     *   "data": {
     *     "cosine": 0.7823,
     *     "label": "语义相近",
     *     "costMs": 42
     *   }
     * }
     * </pre>
     */
    @PostMapping("/compare")
    public Map<String, Object> compare(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            String textA = (String) body.get("textA");
            String textB = (String) body.get("textB");

            if (textA == null || textA.trim().isEmpty() || textB == null || textB.trim().isEmpty()) {
                response.put("code", 400);
                response.put("msg", "textA 和 textB 均不能为空");
                return response;
            }

            return similarityService.compareSimilarity(textA.trim(), textB.trim());

        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "服务器内部错误: " + e.getMessage());
            return response;
        }
    }

    /**
     * 业务功能：对 ES 全库文档批量计算与查询文本的余弦相似度（用于分析向量空间分布）
     * 关键流程：接收 queryText + 可选 index + limit →
     *           SimilarityService#corpusScan（ES scroll + 批量打分 + 分布统计）
     *
     * 请求体示例：
     * <pre>
     * {
     *   "queryText": "保密义务",
     *   "index": "knowledge_base_*",   // 可选，默认 knowledge_base_*
     *   "limit": 500                   // 可选，默认 500，最大 5000
     * }
     * </pre>
     *
     * 响应示例：
     * <pre>
     * {
     *   "code": 200,
     *   "data": {
     *     "items": [{ "docId": "xx", "title": "...", "chunkText": "...", "cosine": 0.82, "label": "语义相近" }],
     *     "distribution": [
     *       { "range": "0.0–0.2", "count": 12 },
     *       ...
     *     ],
     *     "total": 500,
     *     "costMs": 12400
     *   }
     * }
     * </pre>
     *
     * 注意：全库扫描耗时与文档数量成正比，建议 limit 不超过 2000 以控制响应时间。
     */
    @PostMapping("/corpus-scan")
    public Map<String, Object> corpusScan(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            String queryText = (String) body.get("queryText");
            if (queryText == null || queryText.trim().isEmpty()) {
                response.put("code", 400);
                response.put("msg", "queryText 不能为空");
                return response;
            }

            String index = (String) body.getOrDefault("index", null);
            // limit 默认 500，控制首次扫描时间
            int limit = 500;
            Object limitObj = body.get("limit");
            if (limitObj instanceof Number) {
                limit = ((Number) limitObj).intValue();
            }

            return similarityService.corpusScan(index, queryText.trim(), limit);

        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "服务器内部错误: " + e.getMessage());
            return response;
        }
    }

    /**
     * 业务功能：文档级相似性搜索 —— 给定一段文本，返回知识库中语义最相似的文档列表。
     * 核心原理：调用 AI Service 对输入文本做均值池化向量化 → KB 中预计算的 doc_vector KNN
     * 关键流程：text → /api/ai/vector/long-doc → doc_vector → kb_doc_meta KNN → top-K 文档
     *
     * 请求体示例：
     * <pre>
     * {
     *   "text": "设立商事调解组织，应当符合下列条件...",
     *   "topK": 5,
     *   "excludeSource": "商事调解条例.docx"   // 可选，排除来源文档自身
     * }
     * </pre>
     *
     * 响应示例：
     * <pre>
     * {
     *   "code": 200,
     *   "data": {
     *     "items": [
     *       { "docId": "uuid", "source": "xxx.docx", "similarity": 0.8712, "chunkCount": 12, "label": "高度相似" },
     *       ...
     *     ],
     *     "total": 5,
     *     "costMs": 120
     *   }
     * }
     * </pre>
     */
    @PostMapping("/similar-docs")
    public Map<String, Object> similarDocs(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            String text = (String) body.get("text");
            if (text == null || text.trim().isEmpty()) {
                response.put("code", 400);
                response.put("msg", "text 不能为空");
                return response;
            }

            int topK = 5;
            Object topKObj = body.get("topK");
            if (topKObj instanceof Number) {
                topK = Math.min(Math.max(((Number) topKObj).intValue(), 1), 20);
            }

            String excludeSource = (String) body.getOrDefault("excludeSource", null);

            return similarityService.findSimilarDocs(text.trim(), topK, excludeSource);

        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "服务器内部错误: " + e.getMessage());
            return response;
        }
    }
}
