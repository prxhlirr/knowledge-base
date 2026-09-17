package com.boyang.search.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchServiceV2TimeoutConfigTest {

    @Test
    void vectorFetchTimeoutUsesDefaultValueWhenNoConfigProvided() {
        SearchServiceV2 service = newService();

        assertEquals(6000, service.resolveVectorFetchTimeoutMs());
    }

    @Test
    void vectorFetchTimeoutUsesConfiguredPositiveValue() {
        SearchServiceV2 service = newService();
        ReflectionTestUtils.setField(service, "vectorFetchTimeoutMs", "4500");

        assertEquals(4500, service.resolveVectorFetchTimeoutMs());
    }

    @Test
    void vectorFetchTimeoutFallbackWhenConfiguredValueIsInvalid() {
        SearchServiceV2 service = newService();
        ReflectionTestUtils.setField(service, "vectorFetchTimeoutMs", "abc");
        assertEquals(6000, service.resolveVectorFetchTimeoutMs());

        ReflectionTestUtils.setField(service, "vectorFetchTimeoutMs", "0");
        assertEquals(6000, service.resolveVectorFetchTimeoutMs());

        ReflectionTestUtils.setField(service, "vectorFetchTimeoutMs", "-1");
        assertEquals(6000, service.resolveVectorFetchTimeoutMs());
    }

    /**
     * 业务功能：构造只包含配置字段的 V2 搜索服务。
     * 关键流程：通过反射模拟 Spring 配置注入，避免单元测试依赖完整 Pipeline Bean。
     */
    private SearchServiceV2 newService() {
        SearchServiceV2 service = new SearchServiceV2();
        ReflectionTestUtils.setField(service, "vectorFetchTimeoutMs", "6000");
        return service;
    }
}
