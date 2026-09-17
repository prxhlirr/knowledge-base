package com.boyang.search.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchControllerTimeoutConfigTest {

    @Test
    void resolveSearchTotalTimeoutMsUsesDefaultWhenBlank() {
        SearchController controller = new SearchController();

        ReflectionTestUtils.setField(controller, "searchTotalTimeoutMs", "");

        assertEquals(10000L, controller.resolveSearchTotalTimeoutMs());
    }

    @Test
    void resolveSearchTotalTimeoutMsUsesConfiguredPositiveValue() {
        SearchController controller = new SearchController();

        ReflectionTestUtils.setField(controller, "searchTotalTimeoutMs", "15000");

        assertEquals(15000L, controller.resolveSearchTotalTimeoutMs());
    }

    @Test
    void resolveSearchTotalTimeoutMsFallsBackForInvalidValue() {
        SearchController controller = new SearchController();

        ReflectionTestUtils.setField(controller, "searchTotalTimeoutMs", "abc");

        assertEquals(10000L, controller.resolveSearchTotalTimeoutMs());
    }

    @Test
    void resolveSearchTotalTimeoutMsFallsBackForZeroOrNegativeValue() {
        SearchController controller = new SearchController();

        ReflectionTestUtils.setField(controller, "searchTotalTimeoutMs", "0");
        assertEquals(10000L, controller.resolveSearchTotalTimeoutMs());

        ReflectionTestUtils.setField(controller, "searchTotalTimeoutMs", "-1");
        assertEquals(10000L, controller.resolveSearchTotalTimeoutMs());
    }

    @Test
    void resolveChunksSizeUsesDefaultValuesWhenBlank() {
        SearchController controller = new SearchController();

        ReflectionTestUtils.setField(controller, "chunksMaxSize", "");
        ReflectionTestUtils.setField(controller, "chunksFallbackSize", "");

        assertEquals(500, controller.resolveChunksMaxSize());
        assertEquals(10, controller.resolveChunksFallbackSize());
    }

    @Test
    void resolveChunksSizeUsesConfiguredPositiveValues() {
        SearchController controller = new SearchController();

        ReflectionTestUtils.setField(controller, "chunksMaxSize", "800");
        ReflectionTestUtils.setField(controller, "chunksFallbackSize", "20");

        assertEquals(800, controller.resolveChunksMaxSize());
        assertEquals(20, controller.resolveChunksFallbackSize());
    }

    @Test
    void resolveChunksSizeFallsBackForInvalidValues() {
        SearchController controller = new SearchController();

        ReflectionTestUtils.setField(controller, "chunksMaxSize", "abc");
        ReflectionTestUtils.setField(controller, "chunksFallbackSize", "-1");

        assertEquals(500, controller.resolveChunksMaxSize());
        assertEquals(10, controller.resolveChunksFallbackSize());
    }
}
