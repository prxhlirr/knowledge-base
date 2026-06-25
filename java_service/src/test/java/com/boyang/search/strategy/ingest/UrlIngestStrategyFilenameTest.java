package com.boyang.search.strategy.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UrlIngestStrategyFilenameTest {

    @Test
    void derivesFilenameFromUrlPathWithoutQueryString() throws Exception {
        assertEquals(
                "检验检测工作.docx",
                UrlIngestStrategy.deriveFilenameFromUrl(
                        "https://example.test/files/%E6%A3%80%E9%AA%8C%E6%A3%80%E6%B5%8B%E5%B7%A5%E4%BD%9C.docx?token=abc"));
    }
}
