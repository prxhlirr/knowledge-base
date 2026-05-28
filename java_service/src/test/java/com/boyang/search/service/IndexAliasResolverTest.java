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
}
