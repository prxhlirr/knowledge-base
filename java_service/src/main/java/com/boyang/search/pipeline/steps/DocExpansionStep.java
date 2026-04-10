package com.boyang.search.pipeline.steps;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.boyang.search.entity.SysTenantPolicy;
import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.pipeline.SearchPipelineStep;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 管线节点 5：文档扩展层 (Doc Expansion & Roll-up)
 * 负责? * - Document Expansion（同一文档不同 chunk 的扩展）
 * - Small-to-Big Retrieval（fine-to-coarse 上下文回溯替换）
 *
 * [P1-3 修复] Sibling 查询 + fine→coarse 父块查询改为并发执行，消除串行等待? * 原逻辑：Sibling ES 查询完成后才开始收?parent_chunk_id，再?Parent ES 查询? *          两次 ES 查询串行，约 0.5~1s 额外延迟? * 修复策略? *   1. 在候选池整理完成后，同时（并发）提交 Sibling ES 请求 ?Parent ES 请求? *   2. allOf 等待两个 Future（超?3s 兜底），两个结果互相独立，逻辑不变? * 注意：Sibling 查询增加?Sibling chunk，?Parent 查询基于原始候选池?fine chunk? *       两阶段逻辑不存在依赖，可以安全并发? */
@Component
public class DocExpansionStep implements SearchPipelineStep {

    @Autowired
    private ElasticsearchClient esClient;

    @Override
    @SuppressWarnings("unchecked")
    public void execute(SearchContext context) throws Exception {
        if (context.getFastTrackDocs() != null && !context.getFastTrackDocs().isEmpty()) {
            return;
        }

        List<Map<String, Object>> candidates = context.getCandidateDocs();
        if (candidates == null || candidates.isEmpty()) return;

        // 保证 candidates ?RRF 分数降序排列
        candidates.sort((a, b) -> {
            double scoreA = a.get("_rrf_score") instanceof Number ? ((Number) a.get("_rrf_score")).doubleValue() : 0.0;
            double scoreB = b.get("_rrf_score") instanceof Number ? ((Number) b.get("_rrf_score")).doubleValue() : 0.0;
            return Double.compare(scoreB, scoreA);
        });

        SysTenantPolicy policy = context.getTenantPolicy();

        // ── 预计算两路查询所需数据（候选池固定，可同步提取）──────────────────────
        // Sibling 查询所需：所有文档的 doc_id（按 source 字段聚合
        Set<String> seenDocIds      = new LinkedHashSet<>();
        Set<String> existingChunkIds = new HashSet<>();
        for (Map<String, Object> c : candidates) {
            existingChunkIds.add((String) c.get("_id"));
            Map<String, Object> src = (Map<String, Object>) c.get("_source");
            if (src != null) {
                Map<String, Object> meta = (Map<String, Object>) src.get("metadata");
                if (meta != null) {
                    String docId = (String) meta.getOrDefault("doc_id", meta.getOrDefault("source", ""));
                    if (docId != null && !docId.isEmpty()) seenDocIds.add(docId);
                }
            }
        }

        // Parent 查询所需：所?fine chunk ?parent_chunk_id 集合
        // 注意：此处基?入场"候选池，Sibling 新增?chunk 尚未加入，完全独
        Set<String> parentIdSet = new LinkedHashSet<>();
        for (Map<String, Object> cand : candidates) {
            Map<String, Object> src = (Map<String, Object>) cand.get("_source");
            if (src != null && "fine".equals(src.get("chunk_granularity"))) {
                String pid = (String) src.get("parent_chunk_id");
                if (pid != null && !pid.isEmpty()) parentIdSet.add(pid);
            }
        }

        // ── [P1-3] 并发发射 Sibling + Parent 两路 ES 查询 ───────────────────────
        // 根因：两路查询互相独立（Sibling 基于 doc_id，Parent 基于 parent_chunk_id），
        //       没有任何数据依赖，串行执行纯属浪?~0.5-1s
        final Set<String> finalSeenDocIds  = Collections.unmodifiableSet(seenDocIds);
        final Set<String> finalParentIdSet = Collections.unmodifiableSet(parentIdSet);

        // Sibling Future
        CompletableFuture<SearchResponse<Object>> siblingFuture;
        if (!finalSeenDocIds.isEmpty()) {
            List<co.elastic.clients.elasticsearch._types.FieldValue> docIdValues = finalSeenDocIds.stream()
                    .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                    .collect(Collectors.toList());
            SearchRequest expansionReq = new SearchRequest.Builder()
                    .index(policy.getIndexPattern())
                    .size(300)
                    .query(q -> q.bool(b -> {
                        b.filter(f -> f.terms(t -> t.field("metadata.source").terms(tv -> tv.value(docIdValues))));
                        b.filter(f -> f.bool(boolQuery -> boolQuery
                                .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                                .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))));
                        return b;
                    }))
                    .build();
            siblingFuture = com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
                try {
                    return esClient.search(expansionReq, Object.class);
                } catch (Exception e) {
                    throw new RuntimeException("[DocExpansion] Sibling query failed: " + e.getMessage(), e);
                }
            });
        } else {
            siblingFuture = CompletableFuture.completedFuture(null);
        }

        // Parent Future
        CompletableFuture<SearchResponse<Object>> parentFuture;
        if (!finalParentIdSet.isEmpty()) {
            List<String> pidList = new ArrayList<>(finalParentIdSet);
            SearchRequest parentReq = new SearchRequest.Builder()
                    .index(policy.getIndexPattern())
                    .size(pidList.size())
                    .query(q -> q.ids(i -> i.values(pidList)))
                    .build();
            parentFuture = com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
                try {
                    return esClient.search(parentReq, Object.class);
                } catch (Exception e) {
                    throw new RuntimeException("[DocExpansion] Parent query failed: " + e.getMessage(), e);
                }
            });
        } else {
            parentFuture = CompletableFuture.completedFuture(null);
        }

        // 等待两路并发完成?s 上限兜底，超时时降级到已有候选池
        try {
            CompletableFuture.allOf(siblingFuture, parentFuture).get(3, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            System.err.println("[DocExpansion] Sibling/Parent query timeout (3s), using existing candidates.");
            siblingFuture.cancel(true);
            parentFuture.cancel(true);
        }

        // ── 处理 Sibling 结果 ─────────────────────────────────────────────────────
        try {
            SearchResponse<Object> expansionResp = !siblingFuture.isCancelled() ? siblingFuture.getNow(null) : null;
            if (expansionResp != null) {
                double minExistingRrf = candidates.stream()
                        .mapToDouble(c -> {
                            Object v = c.get("_rrf_score");
                            return v instanceof Number ? ((Number) v).doubleValue() : 0.0;
                        }).min().orElse(0.01);
                double siblingScore = Math.max(minExistingRrf * 0.75, 0.01);
                int expandedCount = 0;
                final int maxSiblingTotal = seenDocIds.size() * 10;
                for (co.elastic.clients.elasticsearch.core.search.Hit<Object> hit : expansionResp.hits().hits()) {
                    if (expandedCount >= maxSiblingTotal) break;
                    if (!existingChunkIds.contains(hit.id())) {
                        Map<String, Object> siblingDoc = new HashMap<>((Map<String, Object>) hit.source());
                        siblingDoc.put("_source", hit.source());
                        siblingDoc.put("_id", hit.id());
                        siblingDoc.put("_rrf_score", siblingScore);
                        siblingDoc.put("_max_knn_score", 0.0);
                        candidates.add(siblingDoc);
                        existingChunkIds.add(hit.id());
                        expandedCount++;
                    }
                }
                System.out.println("  [DocExpansion] Expanded " + expandedCount + " chunks.");
            }
        } catch (Exception ex) {
            System.err.println(" [DocExpansion] Sibling expansion failed: " + ex.getMessage());
        }

        // ── 处理 Parent 结果（fine→coarse 上下文回溯替换）────────────────────────
        try {
            SearchResponse<Object> parentResp = !parentFuture.isCancelled() ? parentFuture.getNow(null) : null;
            if (parentResp != null) {
                Map<String, Map<String, Object>> parentMap = new LinkedHashMap<>();
                for (co.elastic.clients.elasticsearch.core.search.Hit<Object> hit : parentResp.hits().hits()) {
                    Map<String, Object> pSrc = (Map<String, Object>) hit.source();
                    if (pSrc != null) parentMap.put(hit.id(), pSrc);
                }
                int replaced = 0;
                for (Map<String, Object> cand : candidates) {
                    Map<String, Object> src = (Map<String, Object>) cand.get("_source");
                    if (src == null || !"fine".equals(src.get("chunk_granularity"))) continue;
                    String pid = (String) src.get("parent_chunk_id");
                    if (pid == null || !parentMap.containsKey(pid)) continue;

                    Map<String, Object> pSrc = parentMap.get(pid);
                    String parentText = (String) pSrc.get("content");
                    if (parentText != null && !parentText.isEmpty()) {
                        src.put("content", parentText);
                        Map<String, Object> meta = (Map<String, Object>) src.get("metadata");
                        if (meta != null) meta.put("chunk_text", parentText);
                        replaced++;
                    }
                }
                System.out.println("  [fine->coarse] Replaced content of " + replaced + " fine candidates.");
            }
        } catch (Exception ex) {
            System.err.println(" [fine->coarse] Roll-up failed: " + ex.getMessage());
        }

        System.out.println("====== [Pipeline] Node 5: DocExpansion ======");
        System.out.println("  - Final Pool Size: " + candidates.size());
    }
}

