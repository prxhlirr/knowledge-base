package com.boyang.search.pipeline.steps;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.util.ObjectBuilder;
import com.boyang.search.entity.SysAiTuningConfig;
import com.boyang.search.service.SysGovSynonymService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * ES 召回公共工具类
 *
 * 业务功能：封装三种召回策略（Keyword/Semantic/Hybrid）的共享逻辑，避免代码重复。
 * 主要提供以下能力：
 *   1. buildLegacyPermFilter  - 统一权限过滤 DSL（新旧架构混合双模，BM25/KNN/Sparse/PreFlight 共用）
 *   2. buildDeptValues        - 部门编码层级展开（旧架构 DEPT 类文档权限匹配）
 *   3. extractCoreTerms       - 核心词提取（BM25 Boost^20/^30 加权用）
 *   4. generateFallbackSnippet- 摘要截取（ES highlight 字段为空时的兜底）
 *   5. highlightText          - Java 层高亮标注（snippet 中命中词加 <em> 标签）
 *   6. expandSynonyms         - 同义词展开（BM25 should 子句注入）
 *
 * 设计说明：
 *   此类是无状态工具组件（@Component），所有方法以 context 信息或显式参数为输入，
 *   不持有任何请求级别的状态，可被多个 Strategy 并发调用而不存在线程安全问题。
 */
@Component
public class EsRecallUtils {

    @Autowired
    private SysGovSynonymService govSynonymService;

    // ─── 权限过滤 ────────────────────────────────────────────────────────────

    /**
     * 统一权限过滤 DSL 构建器（混合双模架构）
     *
     * 核心逻辑：
     *   分支A（新架构）：文档 acl_tokens 字段与用户 aclTokens 集合 terms 求交，O(1) 判定；
     *   分支B（旧架构降级）：文档无 acl_tokens 字段（历史数据），降级使用 visibility 多路 bool 判定；
     *   超管旁路：aclTokens 中包含 _SUPER_ADMIN 时直接返回 match_all 放行全部文档。
     *
     * BM25、KNN、Sparse、Pre-Flight 四路查询共用此方法，确保权限校验逻辑在全链路保持一致。
     *
     * @param isAnonymous 是否为匿名用户（userId 为空）
     * @param userId      当前用户 ID
     * @param deptValues  用户部门编码展开的层级列表（旧架构 DEPT 类文档过滤用）
     * @return ES Filter Query Builder Function
     */
    public Function<Query.Builder, ObjectBuilder<Query>> buildLegacyPermFilter(
            boolean isAnonymous,
            String userId,
            List<FieldValue> deptValues) {

        Set<String> aclTokens = com.boyang.search.security.UserContextHolder.getAclTokens();

        // 超管旁路：包含 _SUPER_ADMIN token 直接 match_all 放行
        if (aclTokens.contains("_SUPER_ADMIN")) {
            return f -> f.matchAll(m -> m);
        }

        // 构建 terms 查询所需的值列表
        List<FieldValue> tokenValues = new ArrayList<>();
        for (String token : aclTokens) {
            tokenValues.add(FieldValue.of(token));
        }

        return f -> f.bool(mixedBool -> {
            // 分支A：新架构 —— acl_tokens 与用户 token 集合求交，命中任一即有权
            mixedBool.should(s -> s.terms(t -> t.field("acl_tokens")
                .terms(tv -> tv.value(tokenValues))));

            // 分支B：旧架构降级 —— 文档不存在 acl_tokens 字段（存量历史数据）
            mixedBool.should(s -> s.bool(legacyBool -> {
                legacyBool.mustNot(mn -> mn.exists(e -> e.field("acl_tokens")));
                legacyBool.must(m -> m.bool(permBool -> {
                    if (isAnonymous) {
                        permBool.should(sh -> sh.term(t -> t.field("metadata.visibility").value("PUBLIC")));
                        permBool.should(sh -> sh.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.visibility")))));
                    } else {
                        permBool.should(sh -> sh.terms(t -> t.field("metadata.visibility")
                            .terms(tv -> tv.value(Arrays.asList(
                                FieldValue.of("PUBLIC"),
                                FieldValue.of("INTERNAL"))))));
                        permBool.should(sh -> sh.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.visibility")))));
                        if (!deptValues.isEmpty()) {
                            permBool.should(sh -> sh.terms(t -> t.field("metadata.dept_code_full")
                                .terms(tv -> tv.value(deptValues))));
                        }
                        // PRIVATE：仅上传者本人可见
                        permBool.should(sh -> sh.bool(bPrivate -> bPrivate
                            .must(m1 -> m1.term(t -> t.field("metadata.visibility").value("PRIVATE")))
                            .must(m2 -> m2.term(t -> t.field("metadata.uploader_id").value(userId)))));
                        // GRANT：授权用户可见
                        permBool.should(sh -> sh.bool(bGrant -> bGrant
                            .must(m1 -> m1.term(t -> t.field("metadata.visibility").value("GRANT")))
                            .must(m2 -> m2.term(t -> t.field("metadata.granted_users").value(userId)))));
                    }
                    permBool.minimumShouldMatch("1");
                    return permBool;
                }));
                return legacyBool;
            }));

            // 分支A 或分支B 命中其一即通过
            mixedBool.minimumShouldMatch("1");
            return mixedBool;
        });
    }

    /**
     * 统一构建最新版本文档的过滤 DSL 条件。
     * 过滤规则：只取 metadata.is_latest = true 的记录，或者兼容该字段完全不存在的历史存量记录。
     * 
     * @return ES Filter Query Builder Function
     */
    public java.util.function.Function<co.elastic.clients.elasticsearch._types.query_dsl.Query.Builder, co.elastic.clients.util.ObjectBuilder<co.elastic.clients.elasticsearch._types.query_dsl.Query>> buildLatestVersionFilter() {
        return ft -> ft.bool(boolQuery -> boolQuery
                .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
        );
    }

    /**
     * 将用户部门编码展开为层级前缀列表。
     *
     * 业务功能：旧架构 DEPT 类文档使用 metadata.dept_code_full 字段标记可见部门范围，
     * 通过展开前缀层级匹配"本部门及上级部门发布的文档"。
     * 例如：deptCode="620102000000" 展开为 ["620102000000"]（以"-"为分隔符时展开层级）
     *
     * @param deptCode 用户当前部门编码
     * @return 层级前缀 FieldValue 列表，空字符串时返回空列表
     */
    public List<FieldValue> buildDeptValues(String deptCode) {
        if (deptCode == null || deptCode.isEmpty()) return new ArrayList<>();
        List<FieldValue> list = new ArrayList<>();
        String[] parts = deptCode.split("-");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append("-");
            sb.append(p);
            list.add(FieldValue.of(sb.toString()));
        }
        return list;
    }

    // ─── 文本处理 ────────────────────────────────────────────────────────────

    /**
     * 从归一化查询中提取核心锚词（BM25 Boost 加权用）。
     *
     * 业务功能：
     *   - 2~4 字短词直接以自身为锚词（短词即核心）
     *   - 长查询过滤 DB 噪词表，提取最多 3 个核心词用于 BM25 match^20 + matchPhrase^30 强制加权
     *   - 锚词显著提升精确命中排名，解决短公文关键词被长文档稀释的问题
     *
     * @param query  归一化后的查询词
     * @param config 调参配置（读取 noiseWords 停用词列表）
     * @return 核心锚词列表（最多 3 个）
     */
    public List<String> extractCoreTerms(String query, SysAiTuningConfig config) {
        List<String> coreTerms = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return coreTerms;
        String trimmed = query.trim();
        // 短词（2~4字）：整词即为语义核心，直接作为唯一锚词
        if (trimmed.length() >= 2 && trimmed.length() <= 4) {
            coreTerms.add(trimmed);
            System.out.println("[CoreTermAnchor] Short query self-anchor: [" + trimmed + "]");
            return coreTerms;
        }
        // 长查询：过滤噪词 + 提取中文词段
        String remaining = trimmed;
        List<String> noiseList = config != null ? config.getNoiseWordList() : Collections.emptyList();
        for (String noise : noiseList) {
            if (noise == null || noise.isEmpty()) continue;
            remaining = remaining.replace(noise, " ");
        }
        remaining = remaining.replaceAll("[^\\u4e00-\\u9fa5]+", " ").trim();
        String[] fragments = remaining.split("\\s+");
        for (String frag : fragments) {
            if (frag.length() >= 2 && !coreTerms.contains(frag)) {
                coreTerms.add(frag);
                if (coreTerms.size() >= 3) break;
            }
        }
        if (!coreTerms.isEmpty()) {
            System.out.println("[CoreTermAnchor] Extracted: " + coreTerms + " from: " + query);
        }
        return coreTerms;
    }

    /**
     * 从查询词中剔除噪词（停用词/虚词），返回只包含实词的干净字符串。
     *
     * 业务功能：ES highlight 默认使用与查询相同的分词 token 做高亮标注，
     * 导致"的/了/是"等虚词也被高亮。此方法在构造 highlight_query 前过滤掉这些词，
     * 使高亮只标注有意义的实词。
     *
     * @param query  归一化后的查询词
     * @param config 调参配置（读取 noiseWords 停用词列表）
     * @return 剔除噪词后的查询字符串（保留原始顺序），若全部被过滤则返回原始查询
     */
    public String stripNoiseWords(String query, SysAiTuningConfig config) {
        if (query == null || query.trim().isEmpty()) return query;
        List<String> noiseList = config != null ? config.getNoiseWordList() : Collections.emptyList();
        String remaining = query;
        for (String noise : noiseList) {
            if (noise == null || noise.isEmpty()) continue;
            remaining = remaining.replace(noise, "");
        }
        // [单字过滤] 按空格切分后剔除单字符 token，避免"人/的/了"等单字参与高亮和 BM25
        String[] parts = remaining.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.length() >= 2) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(part);
            }
        }
        remaining = sb.toString().trim();
        return remaining.isEmpty() ? query : remaining;
    }

    /**
     * 从文档原始 content 中定位并截取关键词周边摘要。
     *
     * 业务功能：当 ES highlight 字段为空（KNN 检索或 highlight 配置未触发）时，
     * 在 Java 层对原始 content 进行关键词定位截取，作为摘要兜底。
     *
     * @param content   文档原始 content 字段
     * @param queryText 原始用户查询词（用于定位摘要起点）
     * @return 截取的摘要文本（含省略号前缀/后缀）
     */
    public String generateFallbackSnippet(String content, String queryText) {
        if (content == null || content.isEmpty()) return "";
        // OOM guard：replaceAll 对超长字符串会构建等量 StringBuffer，需先截断
        final int MAX_CONTENT = 2000;
        String safeContent = content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT) : content;
        // 清除形如 [语篇语境: 乡道] 的上下文标注前缀
        String cleanContent = safeContent.replaceAll("\\[[^\\]]*:[^\\]]*\\]", "").trim();
        if (queryText == null || queryText.isEmpty()) {
            return cleanContent.substring(0, Math.min(cleanContent.length(), 100));
        }
        int idx = cleanContent.indexOf(queryText);
        if (idx == -1) {
            return cleanContent.substring(0, Math.min(cleanContent.length(), 100));
        }
        int start = Math.max(0, idx - 40);
        int end   = Math.min(cleanContent.length(), idx + queryText.length() + 60);
        String snippet = cleanContent.substring(start, end);
        if (start > 0) snippet = "..." + snippet;
        if (end < cleanContent.length()) snippet = snippet + "...";
        return snippet;
    }

    /**
     * Java 层高亮：对 snippet 中命中的查询词包裹 <em class='highlight'> 标签。
     *
     * 业务功能：当 ES highlight 字段为空时，通过 Java 层对 generateFallbackSnippet 返回
     * 的摘要手动添加高亮标记，保证前端展示效果一致。
     * 按词长降序替换，防止短词提前替换破坏长词标记完整性。
     *
     * @param text  待高亮的摘要文本
     * @param query 查询词（空格分隔多词）
     * @return 含 <em> 标签的高亮文本
     */
    public String highlightText(String text, String query) {
        if (text == null || query == null || query.trim().isEmpty()) return text;
        // OOM Guard：限定高亮操作的文本长度上界
        final int MAX_HIGHLIGHT_LEN = 1500;
        String safeText = text.length() > MAX_HIGHLIGHT_LEN ? text.substring(0, MAX_HIGHLIGHT_LEN) : text;
        Set<String> kwSet = new HashSet<>(Arrays.asList(query.split("\\s+")));
        List<String> sortedKws = new ArrayList<>(kwSet);
        // 按词长降序：长词优先匹配，防止短词替换后破坏长词 pattern
        sortedKws.sort((a, b) -> Integer.compare(b.length(), a.length()));
        String result = safeText;
        for (String kw : sortedKws) {
            // 过滤单字噪词（的/了/是/在等），避免虚词被高亮
            if (kw.trim().length() < 2) continue;
            try {
                String pattern = "(?i)(" + Pattern.quote(kw) + ")";
                result = result.replaceAll(pattern, "<em class='highlight'>$1</em>");
            } catch (Exception e) {
                // 正则异常时跳过该词，不影响其他词的高亮
            }
        }
        return result;
    }

    /**
     * 获取政务同义词展开 Map。
     *
     * @return 同义词Map，key 为原词，value 为展开词列表；null 表示同义词服务未初始化
     */
    public Map<String, List<String>> getSynonymMap() {
        return govSynonymService.getSynonymMap();
    }
}
