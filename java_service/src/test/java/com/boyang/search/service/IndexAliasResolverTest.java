package com.boyang.search.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexAliasResolverTest {

    private final IndexAliasResolver resolver = new IndexAliasResolver();

    @Test
    void legacyWildcardNormalizesToReadAlias() {
        assertEquals("kb_document", resolver.normalizeReadScope("kb_document*"));
    }

    @Test
    void writeAliasNormalizesToPhysicalIndex() {
        assertEquals("kb_document_policy", resolver.normalizeWriteTarget("kb_document_policy_write"));
    }

    @Test
    void readScopeAcceptsPhysicalIndexAndReadAlias() {
        String scope = resolver.normalizeReadScope("kb_document_policy,kb_document");

        assertTrue(scope.contains("kb_document_policy"));
        assertTrue(scope.contains("kb_document"));
    }

    @Test
    void unsupportedWriteTargetIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.normalizeWriteTarget("external_index"));
    }

    @Test
    void toLogicalIndexStripsVersionSuffix() {
        assertEquals("kb_document_official", resolver.toLogicalIndex("kb_document_official_v2"));
        assertEquals("kb_document_news", resolver.toLogicalIndex("kb_document_news_v2"));
        // 正则兼容未来 _v3 重建，无需再改常量
        assertEquals("kb_document_official", resolver.toLogicalIndex("kb_document_official_v3"));
        assertEquals("kb_document_law", resolver.toLogicalIndex("kb_document_law_v3"));
    }

    @Test
    void toLogicalIndexLeavesLegacyV1AndBareLogicalNamesUntouched() {
        // 回归守护：kb_document_v1 不含 _v2 后缀，必须原样返回（否则会被错并到读别名键上）
        assertEquals("kb_document_v1", resolver.toLogicalIndex("kb_document_v1"));
        assertEquals("kb_document_official", resolver.toLogicalIndex("kb_document_official"));
    }
}
