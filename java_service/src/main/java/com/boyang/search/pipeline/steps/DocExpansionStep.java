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
 * [P1-3 修复] Sibling 查询 + fine→coarse 父块查询改为并发执行，消除串行等待? * 原逻辑：Sibling ES
 * 查询完成后才开始收?parent_chunk_id，再?Parent ES 查询? * 两次 ES 查询串行，约 0.5~1s 额外延迟? * 修复策略?
 * * 1. 在候选池整理完成后，同时（并发）提交 Sibling ES 请求 ?Parent ES 请求? * 2. allOf 等待两个
 * Future（超?3s 兜底），两个结果互相独立，逻辑不变? * 注意：Sibling 查询增加?Sibling chunk，?Parent
 * 查询基于原始候选池?fine chunk? * 两阶段逻辑不存在依赖，可以安全并发?
 */
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

        // [T-keyword] keyword 模式：跳过文档扩展。
        // 根因：keyword 用户意图是精确文档检索，BM25 已直接命中所需文档；
        // Sibling(size=300) + Parent 两路 ES 查询纯属浪费，~300ms~1s 额外延迟。
        if ("keyword".equals(context.getSearchMode())) {
            System.out.println("[DocExpansion] 跳过：keyword 模式不做文档扩展");
            return;
        }

        List<Map<String, Object>> candidates = context.getCandidateDocs();
        if (candidates == null || candidates.isEmpty())
            return;

        // 保证 candidates ?RRF 分数降序排列
        candidates.sort((a, b) -> {
            double scoreA = a.get("_rrf_score") instanceof Number ? ((Number) a.get("_rrf_score")).doubleValue() : 0.0;
            double scoreB = b.get("_rrf_score") instanceof Number ? ((Number) b.get("_rrf_score")).doubleValue() : 0.0;
            return Double.compare(scoreB, scoreA);
        });

        SysTenantPolicy policy = context.getTenantPolicy();
        String indexPattern = context.getResolvedIndexPattern() != null
                ? context.getResolvedIndexPattern()
                : policy.getIndexPattern();

        // ── 预计算两路查询所需数据（候选池固定，可同步提取）──────────────────────
        // Sibling 查询所需：所有文档的 doc_id（按 source 字段聚合
        Set<String> seenDocIds = new LinkedHashSet<>();
        Set<String> existingChunkIds = new HashSet<>();
        for (Map<String, Object> c : candidates) {
            existingChunkIds.add((String) c.get("_id"));
            Map<String, Object> src = (Map<String, Object>) c.get("_source");
            if (src != null) {
                Map<String, Object> meta = (Map<String, Object>) src.get("metadata");
                if (meta != null) {
                    String docId = (String) meta.getOrDefault("doc_id", meta.getOrDefault("source", ""));
                    if (docId != null && !docId.isEmpty())
                        seenDocIds.add(docId);
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
                if (pid != null && !pid.isEmpty())
                    parentIdSet.add(pid);
            }
        }

        // ── [P1-3] 并发发射 Sibling + Parent 两路 ES 查询 ───────────────────────
        // 根因：两路查询互相独立（Sibling 基于 doc_id，Parent 基于 parent_chunk_id），
        // 没有任何数据依赖，串行执行纯属浪?~0.5-1s
        final Set<String> finalSeenDocIds = Collections.unmodifiableSet(seenDocIds);
        final Set<String> finalParentIdSet = Collections.unmodifiableSet(parentIdSet);

        // Sibling Future
        CompletableFuture<SearchResponse<Object>> siblingFuture;
        if (!finalSeenDocIds.isEmpty()) {
            List<co.elastic.clients.elasticsearch._types.FieldValue> docIdValues = finalSeenDocIds.stream()
                    .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                    .collect(Collectors.toList());
            SearchRequest expansionReq = new SearchRequest.Builder()
                    .index(indexPattern)
                    .size(300)
                    .query(q -> q.bool(b -> {
                        b.filter(f -> f.terms(t -> t.field("metadata.source").terms(tv -> tv.value(docIdValues))));
                        b.filter(f -> f.bool(boolQuery -> boolQuery
                                .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                                .should(s -> s.bool(
                                        bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))));
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
                    .index(indexPattern)
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
                    if (expandedCount >= maxSiblingTotal)
                        break;
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

        // ── [Layer2] 文档内信息密度重排：提升高信息密度 chunk 的 _rrf_score ────────────────
        // 设计原则：公示/法规类文档的前言 chunk 和正文 chunk 在 RRF 分数上差异不大。
        // 给「信息密度高的 chunk」注入 ×1.3 加成，让其在后续 LTR 打分中更容易成为
        // Layer1 selectBestChunk 的胜出者。
        try {
            Map<String, List<Map<String, Object>>> docGroups = new LinkedHashMap<>();
            for (Map<String, Object> cand : candidates) {
                Map<String, Object> src = (Map<String, Object>) cand.get("_source");
                String docKey = "_";
                if (src != null) {
                    Map<String, Object> m = (Map<String, Object>) src.get("metadata");
                    if (m != null) {
                        String did = (String) m.getOrDefault("doc_id", m.getOrDefault("source", ""));
                        docKey = did != null ? did : "_";
                    }
                }
                docGroups.computeIfAbsent(docKey, k -> new ArrayList<>()).add(cand);
            }
            int boostedCount = 0;
            for (Map.Entry<String, List<Map<String, Object>>> grpEntry : docGroups.entrySet()) {
                List<Map<String, Object>> group = grpEntry.getValue();
                if (group.size() <= 1)
                    continue;
                int bestDensity = Integer.MIN_VALUE;
                Map<String, Object> bestCand = null;
                String groupDocType = "";
                double groupMaxRrf = 0.0;
                // 确认组内文档类型（只处理 公示/法规），同时找组内最高 _rrf_score
                for (Map<String, Object> cand : group) {
                    Map<String, Object> src = (Map<String, Object>) cand.get("_source");
                    Map<String, Object> meta = src != null ? (Map<String, Object>) src.get("metadata") : null;
                    if (meta != null && groupDocType.isEmpty()) {
                        groupDocType = String.valueOf(meta.getOrDefault("doc_type", ""));
                    }
                    Object rrf = cand.get("_rrf_score");
                    double rrfVal = rrf instanceof Number ? ((Number) rrf).doubleValue() : 0.0;
                    if (rrfVal > groupMaxRrf)
                        groupMaxRrf = rrfVal;
                }
                if (!"公示".equals(groupDocType) && !"法规".equals(groupDocType))
                    continue;
                for (Map<String, Object> cand : group) {
                    Map<String, Object> src = (Map<String, Object>) cand.get("_source");
                    int density = calcChunkInfoDensity(src, groupDocType);
                    if (density > bestDensity) {
                        bestDensity = density;
                        bestCand = cand;
                    }
                }
                if (bestCand != null && groupMaxRrf > 0) {
                    // [Layer2 修复] 根因：语义模式下 sibling chunk 分数≈0.01（占位分），
                    // 乘以1.3后仍可能被 Quality Breaker 过滤，导致 Layer1 selectBestChunk 无法对比。
                    // 修复：将最高密度 chunk 的分数提升至 组内最高分×0.90（竞争分），
                    // 确保它无论是直接召回还是 sibling扩展，都能通过 Quality Breaker 进入竞争。
                    Object curRrf = bestCand.get("_rrf_score");
                    double curScore = curRrf instanceof Number ? ((Number) curRrf).doubleValue() : 0.0;
                    double targetScore = groupMaxRrf * 0.90; // 竞争分 = 组内最高分的90%
                    double newScore = Math.max(curScore * 1.3, targetScore); // 不低于现有分×1.3
                    bestCand.put("_rrf_score", newScore);
                    boostedCount++;
                    System.out.printf("  [Layer2] doc=%s density=%d groupMax=%.4f newScore=%.4f%n",
                            groupDocType, bestDensity, groupMaxRrf, newScore);
                }
            }
            if (boostedCount > 0)
                System.out.printf("  [Layer2] Boosted %d high-density chunks to compete vs Quality Breaker.%n",
                        boostedCount);
        } catch (Exception ex) {
            System.err.println("  [Layer2] Intra-doc rerank failed (non-fatal): " + ex.getMessage());
        }
        // ── 处理 Parent 结果（fine→coarse 上下文回溯替换）────────────────────────
        try {
            SearchResponse<Object> parentResp = !parentFuture.isCancelled() ? parentFuture.getNow(null) : null;
            if (parentResp != null) {
                Map<String, Map<String, Object>> parentMap = new LinkedHashMap<>();
                for (co.elastic.clients.elasticsearch.core.search.Hit<Object> hit : parentResp.hits().hits()) {
                    Map<String, Object> pSrc = (Map<String, Object>) hit.source();
                    if (pSrc != null)
                        parentMap.put(hit.id(), pSrc);
                }
                int replaced = 0;
                for (Map<String, Object> cand : candidates) {
                    Map<String, Object> src = (Map<String, Object>) cand.get("_source");
                    if (src == null || !"fine".equals(src.get("chunk_granularity")))
                        continue;
                    String pid = (String) src.get("parent_chunk_id");
                    if (pid == null || !parentMap.containsKey(pid))
                        continue;

                    Map<String, Object> pSrc = parentMap.get(pid);
                    String parentText = (String) pSrc.get("content");
                    if (parentText != null && !parentText.isEmpty()) {
                        // [架构解耦 P0] 不覆盖 Fine Chunk 的原始 content 字段。
                        // 根因：ES highlight 在召回阶段（DocExpansionStep 之前）已基于 content 打上高亮标记，
                        // 若此处覆盖 content，则 RerankStep 读到的 content 是父块内容，
                        // 但 highlight 字段仍是 Fine Chunk 的内容片段 → 两者不一致。
                        // 修复：改写 display_content（独立字段），供 Reranker 输入时使用（L83 读），
                        // content 保持原始 Fine Chunk 内容不变，确保：
                        // 搜索结果摘要（来自 highlight/content）= 分片明细（来自 _source.content）
                        src.put("display_content", parentText);
                        // 同步更新 granularity 标签，供下游 Collapsing 按 coarse 优先排序
                        src.put("chunk_granularity", "coarse");
                        Map<String, Object> meta = (Map<String, Object>) src.get("metadata");
                        if (meta != null)
                            meta.put("chunk_text", parentText);
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

    /**
     * [Layer2] 文档内 chunk 信息密度评分（DocExpansionStep 内部使用）。
     * 与 RerankStep.calcChunkInfoScore 逻辑一致，维护时两处需同步修改。
     * 规则：section_path层级+3, 人员/条文词+2, 长度适中+1, 套话-2, 空sectionPath-2
     */
    @SuppressWarnings("unchecked")
    private int calcChunkInfoDensity(Map<String, Object> source, String docType) {
        if (source == null)
            return 0;
        int score = 0;
        String sp = (String) source.getOrDefault("section_path", "");
        if (sp == null || sp.isEmpty() || "null".equals(sp)) {
            Map<String, Object> meta = (Map<String, Object>) source.get("metadata");
            if (meta != null)
                sp = String.valueOf(meta.getOrDefault("section_path", ""));
        }
        if (sp != null && (sp.contains("/") || sp.matches(".*第.{1,4}条.*")))
            score += 3;
        else if (sp == null || sp.isEmpty() || "null".equals(sp))
            score -= 2;

        String content = (String) source.getOrDefault("content", "");
        if (content == null)
            content = "";
        String snip = content.length() > 300 ? content.substring(0, 300) : content;
        if ("公示".equals(docType)) {
            if (snip.contains("姓名") || snip.contains("拟任") || snip.contains("候选")
                    || snip.contains("人员") || snip.contains("现任") || snip.contains("拟提拔"))
                score += 2;
            // 数字编号+中文姓名格式（"1. 张三"）额外加分
            if (content.matches("[\\s\\S]*\\d+\\.\\s*[\\u4e00-\\u9fff]{2,4}[，：,\\s][\\s\\S]*"))
                score += 2;
            // 章节引言句（以"如下："结尾）负分
            String tc = content.trim();
            if (tc.endsWith("如下：") || tc.endsWith("以下：") || tc.endsWith("如下") || tc.endsWith("如下。"))
                score -= 3;
        }
        if ("法规".equals(docType)
                && (snip.matches("第[一二三四五六七八九十\\d]+条.*") || snip.contains("本条") || snip.contains("本款")))
            score += 2;
        if ((snip.contains("根据") || snip.contains("按照") || snip.contains("依据"))
                && (snip.contains("规定") || snip.contains("条例") || snip.contains("细则")))
            score -= 2;
        if (snip.contains("特此公示") || snip.contains("特此通知") || snip.contains("研究决定"))
            score -= 2;
        int len = content.length();
        if (len >= 30 && len <= 300)
            score += 1;
        return score;
    }
}
