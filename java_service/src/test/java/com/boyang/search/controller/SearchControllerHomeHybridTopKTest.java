package com.boyang.search.controller;

import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.qa.QaAnswerProperties;
import com.boyang.search.service.SearchCacheService;
import com.boyang.search.service.SearchServiceV2;
import com.boyang.search.service.SysTenantPolicyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchControllerHomeHybridTopKTest {

    /**
     * 业务功能：验证首页混合检索不会直接使用前端 pageSize 作为后端重排深度。
     * 关键流程：当首页请求 pageSize=50 时，keyword 仍按 50 返回预览，hybrid 只按配置的 12 进入 Pipeline。
     */
    @Test
    void homeSearchLimitsHybridBackendTopKIndependentlyFromPageSize() throws Exception {
        SearchController controller = newFixture("12");
        SearchServiceV2 searchServiceV2 = (SearchServiceV2) ReflectionTestUtils.getField(controller, "searchServiceV2");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("queryText", "再来一");
        body.put("pageSize", 50);
        body.put("pageNum", 1);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Search-AppCode", "qa-app");

        controller.homeSearch(body, request);

        ArgumentCaptor<Integer> hybridTopK = ArgumentCaptor.forClass(Integer.class);
        verify(searchServiceV2, timeout(2000)).hybridSearchContextForHome(
                anyString(), anyString(), hybridTopK.capture(), anyMap(), anyString());

        assertEquals(12, hybridTopK.getValue());
    }

    /**
     * 业务功能：验证首页 hybrid topK 配置解析的容错行为。
     * 关键流程：非法配置必须回退默认 12，避免错误环境变量重新放大 ColBERT 输入。
     */
    @Test
    void resolveHomeHybridTopKFallsBackForInvalidValues() {
        SearchController controller = new SearchController();

        ReflectionTestUtils.setField(controller, "homeHybridTopK", "");
        assertEquals(12, controller.resolveHomeHybridTopK());

        ReflectionTestUtils.setField(controller, "homeHybridTopK", "abc");
        assertEquals(12, controller.resolveHomeHybridTopK());

        ReflectionTestUtils.setField(controller, "homeHybridTopK", "-1");
        assertEquals(12, controller.resolveHomeHybridTopK());
    }

    /**
     * 业务功能：验证首页 hybrid topK 可以通过配置调整。
     * 关键流程：生产环境可用环境变量按机器性能调节候选深度，同时保持默认值可回退。
     */
    @Test
    void resolveHomeHybridTopKUsesConfiguredPositiveValue() {
        SearchController controller = new SearchController();

        ReflectionTestUtils.setField(controller, "homeHybridTopK", "8");

        assertEquals(8, controller.resolveHomeHybridTopK());
    }

    /**
     * 业务功能：构造首页搜索测试夹具。
     * 关键流程：只 mock 外部依赖，保留 Controller 参数编排逻辑，确保测试直接覆盖本次性能问题的根因路径。
     */
    private SearchController newFixture(String homeHybridTopK) throws Exception {
        SearchController controller = new SearchController();
        SearchServiceV2 searchServiceV2 = mock(SearchServiceV2.class);
        SearchCacheService searchCacheService = mock(SearchCacheService.class);
        SysTenantPolicyService sysTenantPolicyService = mock(SysTenantPolicyService.class);

        SearchContext hybridContext = new SearchContext();
        hybridContext.setStartTime(System.currentTimeMillis());
        hybridContext.setFinalResult(Collections.emptyList());
        hybridContext.setAnswerQaHits(Collections.emptyList());

        when(searchServiceV2.hybridSearchV2(anyString(), anyString(), anyInt(), anyMap(), anyString()))
                .thenReturn(Collections.emptyList());
        when(searchServiceV2.hybridSearchContextForHome(anyString(), anyString(), anyInt(), anyMap(), anyString()))
                .thenReturn(hybridContext);

        ReflectionTestUtils.setField(controller, "searchServiceV2", searchServiceV2);
        ReflectionTestUtils.setField(controller, "searchCacheService", searchCacheService);
        ReflectionTestUtils.setField(controller, "sysTenantPolicyService", sysTenantPolicyService);
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(controller, "qaAnswerProperties", new QaAnswerProperties());
        ReflectionTestUtils.setField(controller, "searchTotalTimeoutMs", "3000");
        ReflectionTestUtils.setField(controller, "homeHybridTopK", homeHybridTopK);
        ReflectionTestUtils.setField(controller, "jwtDevMode", true);
        return controller;
    }
}
