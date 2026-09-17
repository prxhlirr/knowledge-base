package com.boyang.search.pipeline.steps;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.boyang.search.security.JwtVerifier;
import com.boyang.search.security.UserContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsRecallUtilsTest {

    private final EsRecallUtils utils = new EsRecallUtils();

    @Test
    void readableSourceIndexValuesKeepPhysicalAndLogicalVersionedDocumentIndexes() {
        List<FieldValue> values = utils.buildReadableSourceIndexValues(
                "kb_document_law_v2, kb_document_notice_v3");

        assertEquals(4, values.size());
        assertEquals("kb_document_law_v2", values.get(0).stringValue());
        assertEquals("kb_document_law", values.get(1).stringValue());
        assertEquals("kb_document_notice_v3", values.get(2).stringValue());
        assertEquals("kb_document_notice", values.get(3).stringValue());
    }

    @Test
    void readableSourceIndexValuesKeepUnversionedDocumentIndexes() {
        List<FieldValue> values = utils.buildReadableSourceIndexValues(
                "kb_document_policy, kb_document_notice");

        assertEquals(2, values.size());
        assertEquals("kb_document_policy", values.get(0).stringValue());
        assertEquals("kb_document_notice", values.get(1).stringValue());
    }

    @Test
    void readableSourceIndexValuesCoverAllCurrentVersionedDocumentFamilies() {
        List<FieldValue> values = utils.buildReadableSourceIndexValues(
                "kb_document_official_v2,kb_document_public_v2,kb_document_law_v2,kb_document_news_v2,kb_document_notice_v2");

        assertEquals(10, values.size());
        assertEquals("kb_document_official_v2", values.get(0).stringValue());
        assertEquals("kb_document_official", values.get(1).stringValue());
        assertEquals("kb_document_public_v2", values.get(2).stringValue());
        assertEquals("kb_document_public", values.get(3).stringValue());
        assertEquals("kb_document_law_v2", values.get(4).stringValue());
        assertEquals("kb_document_law", values.get(5).stringValue());
        assertEquals("kb_document_news_v2", values.get(6).stringValue());
        assertEquals("kb_document_news", values.get(7).stringValue());
        assertEquals("kb_document_notice_v2", values.get(8).stringValue());
        assertEquals("kb_document_notice", values.get(9).stringValue());
    }

    @Test
    void readableSourceIndexValuesDoNotGuessReadAliasExpansion() {
        assertTrue(utils.buildReadableSourceIndexValues("kb_document").isEmpty());
        assertTrue(utils.buildReadableSourceIndexValues("kb_document*").isEmpty());
    }

    @Test
    void noReadableIndexBuildsImpossibleSentinelValue() {
        List<FieldValue> values = utils.buildReadableSourceIndexValues("__no_readable_index__");

        assertEquals(1, values.size());
        assertEquals("__no_readable_index__", values.get(0).stringValue());
    }

    @Test
    void docSearchSourceIndexFilterRejectsMissingSourceIndexByDefault() {
        Query query = utils.buildDocSearchSourceIndexFilter("kb_document_law")
                .apply(new Query.Builder())
                .build();

        assertFalse(containsMissingExistsBranch(query, "source_index"));
        assertTrue(containsTermFieldValue(query, "source_index", "__legacy_source_index_denied__"));
    }

    @Test
    void compatibleDocSearchSourceIndexFilterAllowsMissingSourceIndexWhenEnabled() {
        try {
            ReflectionTestUtils.setField(utils, "docSearchMissingSourceIndexAllow", true);

            Query query = utils.buildDocSearchSourceIndexFilter("kb_document_law")
                    .apply(new Query.Builder())
                    .build();

            assertTrue(containsMissingExistsBranch(query, "source_index"));
        } finally {
            ReflectionTestUtils.setField(utils, "docSearchMissingSourceIndexAllow", false);
        }
    }

    @Test
    void strictDocSearchSourceIndexFilterDoesNotAllowMissingSourceIndex() {
        try {
            ReflectionTestUtils.setField(utils, "docSearchMissingSourceIndexAllow", false);

            Query query = utils.buildDocSearchSourceIndexFilter("kb_document_law")
                    .apply(new Query.Builder())
                    .build();

            assertFalse(containsMissingExistsBranch(query, "source_index"));
            assertTrue(containsTermFieldValue(query, "source_index", "__legacy_source_index_denied__"));
        } finally {
            ReflectionTestUtils.setField(utils, "docSearchMissingSourceIndexAllow", false);
        }
    }


    @Test
    void visibleUnitValuesUseCurrentDeptWithoutAncestorExpansion() {
        try {
            UserContextHolder.setIdentity(new JwtVerifier.UserIdentity("u1", "A-A01", "app", null));

            List<FieldValue> values = utils.buildVisibleUnitValuesForCurrentUser();

            assertEquals(2, values.size());
            assertEquals("global", values.get(0).stringValue());
            assertEquals("A-A01", values.get(1).stringValue());
        } finally {
            UserContextHolder.clear();
        }
    }

    @Test
    void visibleUnitValuesIncludeNormalizedAdministrativeDeptCode() {
        try {
            UserContextHolder.setIdentity(new JwtVerifier.UserIdentity("u1", "620102000000", "app", null));

            List<FieldValue> values = utils.buildVisibleUnitValuesForCurrentUser();

            assertEquals(3, values.size());
            assertEquals("global", values.get(0).stringValue());
            assertEquals("620102000000", values.get(1).stringValue());
            assertEquals("620102", values.get(2).stringValue());
        } finally {
            UserContextHolder.clear();
        }
    }

    @Test
    void legacyPermissionFilterIncludesVisibleUnitFields() {
        try {
            UserContextHolder.setIdentity(new JwtVerifier.UserIdentity("u1", "A01", "app", null));

            Query query = utils.buildLegacyPermFilter(false, "u1", utils.buildDeptValues("A01"))
                    .apply(new Query.Builder())
                    .build();

            assertTrue(containsTermsField(query, "acl_tokens"));
            // v2 迁移后读路径只命中纯 keyword 的 v2 索引，.keyword 兼容子句已移除（P6）
            assertFalse(containsTermsField(query, "acl_tokens.keyword"));
            assertTrue(containsTermsField(query, "metadata.acl_tokens"));
            assertFalse(containsTermsField(query, "metadata.acl_tokens.keyword"));
            assertTrue(containsTermsField(query, "visible_unit_codes"));
            assertTrue(containsTermsField(query, "metadata.visible_unit_codes"));
            assertTrue(containsTermFieldValue(query, "metadata.visibility", "DEPT"));
        } finally {
            UserContextHolder.clear();
        }
    }

    @Test
    void docSearchPermissionFilterIncludesTopLevelVisibleUnitFieldOnly() {
        try {
            UserContextHolder.setIdentity(new JwtVerifier.UserIdentity("u1", "A01", "app", null));

            Query query = utils.buildDocSearchPermFilter(false, "u1", utils.buildDeptValues("A01"))
                    .apply(new Query.Builder())
                    .build();

            assertTrue(containsTermsField(query, "acl_tokens"));
            // v2 迁移后 acl_tokens 为纯 keyword，.keyword 兼容子句已移除（P6）
            assertFalse(containsTermsField(query, "acl_tokens.keyword"));
            assertTrue(containsTermsField(query, "visible_unit_codes"));
            assertTrue(!containsTermsField(query, "metadata.visible_unit_codes"));
            assertTrue(containsTermFieldValue(query, "visibility", "DEPT"));
        } finally {
            UserContextHolder.clear();
        }
    }

    @Test
    void strictLegacyPermissionFilterDoesNotAllowMissingMetadataVisibility() {
        try {
            UserContextHolder.setIdentity(new JwtVerifier.UserIdentity("u1", "A01", "app", null));

            Query query = utils.buildLegacyPermFilter(false, "u1", utils.buildDeptValues("A01"))
                    .apply(new Query.Builder())
                    .build();

            assertFalse(containsMissingExistsBranch(query, "metadata.visibility"));
        } finally {
            UserContextHolder.clear();
        }
    }

    @Test
    void strictDocSearchPermissionFilterDoesNotAllowMissingVisibility() {
        try {
            UserContextHolder.setIdentity(new JwtVerifier.UserIdentity("u1", "A01", "app", null));

            Query query = utils.buildDocSearchPermFilter(false, "u1", utils.buildDeptValues("A01"))
                    .apply(new Query.Builder())
                    .build();

            assertFalse(containsMissingExistsBranch(query, "visibility"));
        } finally {
            UserContextHolder.clear();
        }
    }

    @Test
    void compatibleLegacyPermissionFilterAllowsMissingMetadataVisibilityWhenEnabled() {
        try {
            ReflectionTestUtils.setField(utils, "legacyMissingPermissionAllow", true);
            UserContextHolder.setIdentity(new JwtVerifier.UserIdentity("u1", "A01", "app", null));

            Query query = utils.buildLegacyPermFilter(false, "u1", utils.buildDeptValues("A01"))
                    .apply(new Query.Builder())
                    .build();

            assertTrue(containsMissingExistsBranch(query, "metadata.visibility"));
        } finally {
            ReflectionTestUtils.setField(utils, "legacyMissingPermissionAllow", false);
            UserContextHolder.clear();
        }
    }

    @Test
    void compatibleDocSearchPermissionFilterAllowsMissingVisibilityWhenEnabled() {
        try {
            ReflectionTestUtils.setField(utils, "legacyMissingPermissionAllow", true);
            UserContextHolder.setIdentity(new JwtVerifier.UserIdentity("u1", "A01", "app", null));

            Query query = utils.buildDocSearchPermFilter(false, "u1", utils.buildDeptValues("A01"))
                    .apply(new Query.Builder())
                    .build();

            assertTrue(containsMissingExistsBranch(query, "visibility"));
        } finally {
            ReflectionTestUtils.setField(utils, "legacyMissingPermissionAllow", false);
            UserContextHolder.clear();
        }
    }

    private boolean containsTermsField(Query query, String field) {
        if (query == null) {
            return false;
        }
        if (query.isTerms() && field.equals(query.terms().field())) {
            return true;
        }
        if (query.isBool()) {
            for (Query child : query.bool().must()) {
                if (containsTermsField(child, field)) {
                    return true;
                }
            }
            for (Query child : query.bool().should()) {
                if (containsTermsField(child, field)) {
                    return true;
                }
            }
            for (Query child : query.bool().filter()) {
                if (containsTermsField(child, field)) {
                    return true;
                }
            }
            for (Query child : query.bool().mustNot()) {
                if (containsTermsField(child, field)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsTermFieldValue(Query query, String field, String value) {
        if (query == null) {
            return false;
        }
        if (query.isTerm()
                && field.equals(query.term().field())
                && query.term().value() != null
                && value.equals(query.term().value().stringValue())) {
            return true;
        }
        if (query.isBool()) {
            for (Query child : query.bool().must()) {
                if (containsTermFieldValue(child, field, value)) {
                    return true;
                }
            }
            for (Query child : query.bool().should()) {
                if (containsTermFieldValue(child, field, value)) {
                    return true;
                }
            }
            for (Query child : query.bool().filter()) {
                if (containsTermFieldValue(child, field, value)) {
                    return true;
                }
            }
            for (Query child : query.bool().mustNot()) {
                if (containsTermFieldValue(child, field, value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsMissingExistsBranch(Query query, String field) {
        if (query == null) {
            return false;
        }
        if (query.isBool()) {
            for (Query child : query.bool().mustNot()) {
                if (child.isExists() && field.equals(child.exists().field())) {
                    return true;
                }
                if (containsMissingExistsBranch(child, field)) {
                    return true;
                }
            }
            for (Query child : query.bool().must()) {
                if (containsMissingExistsBranch(child, field)) {
                    return true;
                }
            }
            for (Query child : query.bool().should()) {
                if (containsMissingExistsBranch(child, field)) {
                    return true;
                }
            }
            for (Query child : query.bool().filter()) {
                if (containsMissingExistsBranch(child, field)) {
                    return true;
                }
            }
        }
        return false;
    }
}
