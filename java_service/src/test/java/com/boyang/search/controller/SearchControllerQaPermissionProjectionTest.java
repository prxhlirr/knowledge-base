package com.boyang.search.controller;

import com.boyang.search.entity.SysTenantPolicy;
import com.boyang.search.service.SearchCacheService;
import com.boyang.search.service.SearchServiceV2;
import com.boyang.search.service.SysTenantPolicyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SearchControllerQaPermissionProjectionTest {

    /**
     * 验证直接调用 `/search` 业务入口时，Controller 不会裁剪 QA 权限字段。
     *
     * <p>测试只模拟 SearchServiceV2 的最终结果，是因为权限过滤和 QA 检索已在下层测试覆盖；
     * 本用例聚焦 Controller 分页封装、缓存前置判断、AppCode 校验之后的响应字段保留契约。</p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void searchResponseKeepsQaPermissionProjectionFields() throws Exception {
        TestFixture fixture = newFixture();
        List<String> visibleUnits = stubQaSearchResult(fixture.searchServiceV2);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("queryText", "权限字段");
        requestBody.put("pageSize", 10);
        requestBody.put("pageNum", 1);
        requestBody.put("searchMode", "hybrid");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Search-AppCode", "qa-app");

        Map<String, Object> response = fixture.controller.search(requestBody, request);

        assertEquals(200, response.get("code"));
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertEquals(1, list.size());
        Map<String, Object> first = list.get(0);
        assertTrue(Boolean.TRUE.equals(first.get("is_qa_answer")));
        assertEquals("kb_document_official", first.get("source_index"));
        assertEquals("official", first.get("index_code"));
        assertEquals("A01", first.get("owner_unit_code"));
        assertSame(visibleUnits, first.get("visible_unit_codes"));
    }

    /**
     * 验证真实 HTTP JSON 入口不会在序列化响应时丢失 QA 权限字段。
     *
     * <p>该用例使用 MockMvc 覆盖 URL、Header、JSON 请求体和 JSONPath 响应断言；
     * 下游检索仍使用 mock，是为了把测试目标严格限制在 Controller HTTP 出站契约。</p>
     */
    @Test
    void searchHttpJsonResponseKeepsQaPermissionProjectionFields() throws Exception {
        TestFixture fixture = newFixture();
        stubQaSearchResult(fixture.searchServiceV2);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(fixture.controller).build();

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("queryText", "权限字段");
        requestBody.put("pageSize", 10);
        requestBody.put("pageNum", 1);
        requestBody.put("searchMode", "hybrid");

        mockMvc.perform(post("/api/v1/search")
                        .header("X-Search-AppCode", "qa-app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture.objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list[0].is_qa_answer").value(true))
                .andExpect(jsonPath("$.data.list[0].source_index").value("kb_document_official"))
                .andExpect(jsonPath("$.data.list[0].index_code").value("official"))
                .andExpect(jsonPath("$.data.list[0].owner_unit_code").value("A01"))
                .andExpect(jsonPath("$.data.list[0].visible_unit_codes[0]").value("A"))
                .andExpect(jsonPath("$.data.list[0].visible_unit_codes[1]").value("A01"));
    }

    /**
     * 构造只包含 Controller 出站验证所需依赖的测试夹具。
     *
     * <p>业务原因：本组测试不验证 ES、Redis、PG 的真实连通性，
     * 只验证 `/search` 响应封装是否保留权限字段，因此用 mock 固定外部依赖行为。</p>
     */
    private TestFixture newFixture() {
        SearchController controller = new SearchController();
        SearchServiceV2 searchServiceV2 = mock(SearchServiceV2.class);
        SearchCacheService searchCacheService = mock(SearchCacheService.class);
        SysTenantPolicyService sysTenantPolicyService = mock(SysTenantPolicyService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        ObjectMapper objectMapper = new ObjectMapper();

        ReflectionTestUtils.setField(controller, "searchServiceV2", searchServiceV2);
        ReflectionTestUtils.setField(controller, "searchCacheService", searchCacheService);
        ReflectionTestUtils.setField(controller, "sysTenantPolicyService", sysTenantPolicyService);
        ReflectionTestUtils.setField(controller, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(controller, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(controller, "searchTotalTimeoutMs", "3000");
        ReflectionTestUtils.setField(controller, "jwtDevMode", false);
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        SysTenantPolicy policy = new SysTenantPolicy();
        policy.setAppCode("qa-app");
        policy.setAllowedIndices("kb_document_official");
        when(sysTenantPolicyService.getByAppCode("qa-app")).thenReturn(policy);
        when(searchCacheService.buildKey(anyString(), anyString(), anyInt(), anyMap(), anyString()))
                .thenReturn("search:cache:test");
        when(searchCacheService.get("search:cache:test")).thenReturn(null);

        return new TestFixture(controller, searchServiceV2, objectMapper);
    }

    /**
     * 固定 SearchServiceV2 返回一条包含权限投影字段的 QA 命中。
     *
     * <p>业务原因：权限字段是否产生由 Python QA 与 RerankStep 负责；
     * Controller 测试只需要一条已经带字段的最终结果，验证响应层不会二次裁剪。</p>
     */
    private List<String> stubQaSearchResult(SearchServiceV2 searchServiceV2) throws Exception {
        List<String> visibleUnits = Arrays.asList("A", "A01");
        Map<String, Object> qaHit = new LinkedHashMap<>();
        qaHit.put("is_qa_answer", true);
        qaHit.put("chunk_text", "问答命中");
        qaHit.put("file_name", "qa.md");
        qaHit.put("source_index", "kb_document_official");
        qaHit.put("index_code", "official");
        qaHit.put("owner_unit_code", "A01");
        qaHit.put("visible_unit_codes", visibleUnits);

        when(searchServiceV2.hybridSearchV2(anyString(), anyString(), anyInt(), anyMap(), anyString()))
                .thenReturn(Collections.singletonList(qaHit));
        return visibleUnits;
    }

    private static class TestFixture {
        private final SearchController controller;
        private final SearchServiceV2 searchServiceV2;
        private final ObjectMapper objectMapper;

        private TestFixture(SearchController controller, SearchServiceV2 searchServiceV2, ObjectMapper objectMapper) {
            this.controller = controller;
            this.searchServiceV2 = searchServiceV2;
            this.objectMapper = objectMapper;
        }
    }
}
