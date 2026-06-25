package com.boyang.search.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTextNormalizerTest {

    @Test
    void filenameDecodesChinesePercentEncodingWithoutTreatingPlusAsSpace() {
        assertEquals(
                "民法典+A.docx",
                DocumentTextNormalizer.normalizeFilename("%E6%B0%91%E6%B3%95%E5%85%B8+A.docx"));
    }

    @Test
    void metadataDecodesChinesePercentEncoding() {
        assertEquals(
                "行政处罚决定书",
                DocumentTextNormalizer.normalizeMetadataText("%E8%A1%8C%E6%94%BF%E5%A4%84%E7%BD%9A%E5%86%B3%E5%AE%9A%E4%B9%A6"));
    }

    @Test
    void metadataPreservesNormalPercentText() {
        assertEquals("完成率 95%", DocumentTextNormalizer.normalizeMetadataText("完成率 95%"));
    }

    @Test
    void contentOnlyDecodesWhenWholeTextIsEncoded() {
        assertEquals(
                "关于民法典的通知",
                DocumentTextNormalizer.normalizeContentIfFullyEncoded("%E5%85%B3%E4%BA%8E%E6%B0%91%E6%B3%95%E5%85%B8%E7%9A%84%E9%80%9A%E7%9F%A5"));
        assertEquals(
                "参考链接 https://example.test/%E6%B0%91%E6%B3%95%E5%85%B8",
                DocumentTextNormalizer.normalizeContentIfFullyEncoded("参考链接 https://example.test/%E6%B0%91%E6%B3%95%E5%85%B8"));
    }

    @Test
    void detectsEncodedChinese() {
        assertTrue(DocumentTextNormalizer.hasEncodedChinese("%E6%B0%91%E6%B3%95"));
    }
}
