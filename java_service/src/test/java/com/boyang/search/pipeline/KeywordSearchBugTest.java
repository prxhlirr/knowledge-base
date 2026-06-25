package com.boyang.search.pipeline;

import com.boyang.search.security.JwtVerifier;
import com.boyang.search.security.UserContextHolder;
import com.boyang.search.service.SearchServiceV2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务功能：关键词检索 BUG 复现与修复验证集成测试类。
 * 关键方法：
 *   - testKeywordSearchBugReproduction: 重现“输入在正文包含但在元数据中不包含的关键词时，关键词检索结果被错误过滤为空”的 BUG。
 * 流程说明：
 *   1. 模拟 SuperAdmin 超管身份设置 UserContextHolder 上下文。
 *   2. 以关键词模式 ("keyword") 调用 searchServiceV2 检索 "实施"。
 *   3. 在代码修改前，由于 metadata.source 的 term 过滤失效，该用例应断言返回列表为空（以复现 BUG）。
 */
@SpringBootTest(properties = "search.doc-search.enabled=false")
class KeywordSearchBugTest {

    @Autowired
    private SearchServiceV2 searchServiceV2;

    @Autowired
    private com.boyang.search.pipeline.steps.KeywordRecallStrategy keywordRecallStrategy;

    @Autowired
    private co.elastic.clients.elasticsearch.ElasticsearchClient esClient;

    @BeforeEach
    void setUp() {
        // 模拟超级管理员用户身份，跳过租户校验，直连 ES 索引
        io.jsonwebtoken.Claims claims = new io.jsonwebtoken.impl.DefaultClaims();
        claims.put("roles", java.util.Arrays.asList("SYS_ADMIN"));
        JwtVerifier.UserIdentity identity = new JwtVerifier.UserIdentity("admin", "620102000000", "ADMIN_MASTER_KEY", claims);
        identity.setAclTokens(new HashSet<>(java.util.Arrays.asList("_PUBLIC", "_INTERNAL", "ROLE_SUPER_ADMIN")));
        UserContextHolder.setIdentity(identity);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void testKeywordSearchBugReproduction() throws Exception {
        System.out.println("========== [KeywordSearchBugTest] 开始运行关键词检索 BUG 重现测试 ==========");
        
        Map<String, Object> filters = new HashMap<>();
        filters.put("user_id", "admin");
        filters.put("user_dept_code", "620102000000");
        filters.put("target_index", "kb_document_official");

        // 调用关键词搜索
        List<Map<String, Object>> results = searchServiceV2.hybridSearchV2(
                "ADMIN_MASTER_KEY",
                "2025",
                5,
                filters,
                "keyword"
        );

        System.out.println("关键词模式检索 '2025' 返回结果数量: " + results.size());
        for (Map<String, Object> doc : results) {
            System.out.println("  - 文档: " + doc.get("file_name") + " | doc_id: " + doc.get("doc_id"));
            System.out.println("    chunks 数量: " + ((List<?>) doc.get("chunks")).size());
        }

        // 修复后验证阶段：检索结果应该成功召回且不为空，并且对应的 chunks 证据也应该被成功拉取而不为空
        assertTrue(results.size() > 0, "检索结果不应该为空，文档应当被成功召回");
        for (Map<String, Object> doc : results) {
            List<?> chunks = (List<?>) doc.get("chunks");
            assertNotNull(chunks, "证据 chunks 不应当为 null");
            assertTrue(chunks.size() > 0, "证据 chunks 数量应当大于 0，说明证据被正确地拉取到");
        }

        System.out.println("========== [KeywordSearchBugTest] 修复验证测试运行成功 ==========");
    }

    @Test
    void testKeywordSearchFallbackToLegacy() throws Exception {
        System.out.println("========== [KeywordSearchBugTest] 开始运行关键词检索新索引空交集 Fallback 降级测试 ==========");
        
        // 1. 动态将 docSearchEnabled 设置为 true，模拟在当前运行环境下开启新版文档索引
        org.springframework.test.util.ReflectionTestUtils.setField(keywordRecallStrategy, "docSearchEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(keywordRecallStrategy, "docSearchKeywordEnabled", true);
        
        Map<String, Object> filters = new HashMap<>();
        filters.put("user_id", "admin");
        filters.put("user_dept_code", "620102000000");
        filters.put("target_index", "kb_document_official");

        try {
            // 调用关键词搜索，因为新索引没有数据，交集必为空，将自动触发级联降级 Fallback 检索老物理索引
            List<Map<String, Object>> results = searchServiceV2.hybridSearchV2(
                    "ADMIN_MASTER_KEY",
                    "焚烧",
                    5,
                    filters,
                    "keyword"
            );

            System.out.println("Fallback 降级模式检索 '2025' 返回结果数量: " + results.size());
            for (Map<String, Object> doc : results) {
                System.out.println("  - 文档: " + doc.get("file_name") + " | doc_id: " + doc.get("doc_id"));
                System.out.println("    chunks 数量: " + ((List<?>) doc.get("chunks")).size());
            }

            // 修复后验证阶段：即使模拟开启了新索引，由于触发了 Fallback，检索结果应该成功从老索引中召回且不为空，证据 chunks 也不为空
            assertTrue(results.size() > 0, "即使开启新索引，也应当通过 Fallback 机制成功从老索引中召回文档");
            for (Map<String, Object> doc : results) {
                List<?> chunks = (List<?>) doc.get("chunks");
                assertNotNull(chunks, "证据 chunks 不应当为 null");
                assertTrue(chunks.size() > 0, "证据 chunks 数量应当大于 0，说明证据被正确地拉取到");
            }
        } finally {
            // 2. 还原配置，避免影响其他测试用例的默认配置
            org.springframework.test.util.ReflectionTestUtils.setField(keywordRecallStrategy, "docSearchEnabled", false);
        }

        System.out.println("========== [KeywordSearchBugTest] Fallback 降级测试运行成功 ==========");
    }

    @Test
    void testJingningDebug() throws Exception {
        System.out.println("========== [KeywordSearchBugTest] 开始运行静宁县别名调试测试 ==========");
        // 1. 模拟开启新版文档级索引（跟正式 Web 环境完全相同）
        org.springframework.test.util.ReflectionTestUtils.setField(keywordRecallStrategy, "docSearchEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(keywordRecallStrategy, "docSearchKeywordEnabled", true);

        Map<String, Object> filters = new HashMap<>();
        filters.put("user_id", "admin");
        filters.put("user_dept_code", "620102000000");
        // 注意：这里故意不设置 target_index，使其自动路由到 "kb_document" 别名，重现正式环境的行为！

        try {
            List<Map<String, Object>> results = searchServiceV2.hybridSearchV2(
                    "ADMIN_MASTER_KEY",
                    "静宁县",
                    5,
                    filters,
                    "keyword"
            );

            System.out.println("别名模式检索 '静宁县' 返回结果数量: " + results.size());
            for (Map<String, Object> doc : results) {
                System.out.println("  - 文档: " + doc.get("file_name") + " | doc_id: " + doc.get("doc_id"));
                List<?> chunks = (List<?>) doc.get("chunks");
                System.out.println("    chunks 数量: " + (chunks != null ? chunks.size() : "null"));
                if (chunks != null) {
                    for (int j = 0; j < chunks.size(); j++) {
                        Map<?, ?> c = (Map<?, ?>) chunks.get(j);
                        System.out.println("      * chunk " + j + " id: " + c.get("chunk_id"));
                        System.out.println("        chunk_text: " + c.get("chunk_text"));
                    }
                }
            }
        } finally {
            // 2. 还原配置
            org.springframework.test.util.ReflectionTestUtils.setField(keywordRecallStrategy, "docSearchEnabled", false);
        }
        System.out.println("========== [KeywordSearchBugTest] 静宁县别名调试测试结束 ==========");
    }

    @Test
    void testMsearchDebug() throws Exception {
        System.out.println("========== [KeywordSearchBugTest] 开始运行 Msearch Java 端直接调试测试 ==========");
        String indexPattern = "kb_document";
        String docId = "35f59aefe6c292bd349f07aec482af84";
        String fileName = "test_legal_statute.docx";
        List<String> terms = java.util.Arrays.asList("静宁县");

        List<co.elastic.clients.elasticsearch.core.msearch.RequestItem> searches = new java.util.ArrayList<>();
        searches.add(new co.elastic.clients.elasticsearch.core.msearch.RequestItem.Builder()
            .header(h -> h.index(indexPattern))
            .body(b -> {
                b.trackTotalHits(h -> h.enabled(false));
                b.size(50);
                b.source(s -> s.filter(f -> f.includes(
                        "content", "display_content", "chunk_granularity",
                        "metadata.source", "metadata.chunk_id", "metadata.is_latest"
                )));
                b.query(q -> q.bool(bool -> {
                    // 1. 双轨过滤
                    bool.filter(f -> f.bool(boolQuery -> {
                        boolQuery.should(s -> s.term(t -> t.field("metadata.doc_id").value(docId)));
                        if (fileName != null && !fileName.trim().isEmpty()) {
                            boolQuery.should(s -> s.term(t -> t.field("metadata.source").value(fileName)));
                            boolQuery.should(s -> s.matchPhrase(m -> m.field("metadata.source").query(fileName)));
                        }
                        return boolQuery.minimumShouldMatch("1");
                    }));
                    // 2. 最新版本过滤
                    bool.filter(f -> f.bool(boolQuery -> boolQuery
                        .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                        .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                        .minimumShouldMatch("1")
                    ));
                    // 3. 粒度过滤 should
                    bool.should(s -> s.term(t -> t.field("chunk_granularity").value("coarse").boost(5.0f)));
                    bool.should(s -> s.bool(missing -> missing.mustNot(mn -> mn.exists(e -> e.field("chunk_granularity"))).boost(5.0f)));
                    bool.should(s -> s.term(t -> t.field("chunk_granularity").value("fine").boost(1.0f)));
                    
                    // 4. 关键词强匹配 must 子句
                    bool.must(m -> m.bool(anyTerm -> {
                        for (String term : terms) {
                            anyTerm.should(s -> s.bool(oneTerm -> oneTerm
                                .should(ss -> ss.match(mp -> mp.field("content").query(term).analyzer("ik_max_word")))
                                .should(ss -> ss.matchPhrase(mp -> mp.field("content").query(term).slop(0).boost(8.0f)))
                                .should(ss -> ss.match(mp -> mp.field("display_content").query(term).analyzer("ik_max_word").boost(2.0f)))
                                .minimumShouldMatch("1")
                            ));
                        }
                        return anyTerm.minimumShouldMatch("1");
                    }));
                    return bool;
                }));
                return b;
            })
            .build()
        );

        co.elastic.clients.elasticsearch.core.MsearchResponse<Object> response = esClient.msearch(new co.elastic.clients.elasticsearch.core.MsearchRequest.Builder()
            .searches(searches)
            .build(), Object.class);

        System.out.println("Msearch 响应结果: isFailure=" + (response.responses().get(0).isFailure()));
        if (response.responses().get(0).isResult()) {
            co.elastic.clients.elasticsearch.core.msearch.MultiSearchItem<Object> result = response.responses().get(0).result();
            System.out.println("Hits 数量: " + (result.hits() != null ? result.hits().hits().size() : "null"));
            if (result.hits() != null) {
                for (int i = 0; i < result.hits().hits().size(); i++) {
                    System.out.println("  Hit " + i + " ID: " + result.hits().hits().get(i).id());
                }
            }
        } else {
            System.out.println("错误信息: " + response.responses().get(0).failure().error().reason());
        }
        System.out.println("========== [KeywordSearchBugTest] Msearch Java 端直接调试测试结束 ==========");
    }
}


