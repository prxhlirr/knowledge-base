package com.boyang.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.boyang.search.security.JwtVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndexAclGuardTest {

    @Test
    void superAdminCanReadPhysicalIndexEvenWhenIndexHasRoleRules() {
        IndexAclSubjectService aclSubjectService = mock(IndexAclSubjectService.class);
        IndexAclGuard guard = new IndexAclGuard(
                new IndexAliasResolver(),
                aclSubjectService,
                mock(ElasticsearchClient.class));

        String readable = guard.filterReadableScope(
                "kb_document_official",
                superAdminIdentity());

        assertEquals("kb_document_official", readable);
    }

    @Test
    void roleReaderOnlyKeepsAllowedPhysicalIndexWhenRulesExist() {
        IndexAclSubjectService aclSubjectService = mock(IndexAclSubjectService.class);
        JwtVerifier.UserIdentity identity = officialReaderIdentity();
        when(aclSubjectService.decide("kb_document_official", identity, "READ"))
                .thenReturn(IndexAclSubjectService.Decision.ALLOW);
        when(aclSubjectService.hasRules("kb_document_official", "READ")).thenReturn(true);
        when(aclSubjectService.decide("kb_document_public", identity, "READ"))
                .thenReturn(IndexAclSubjectService.Decision.ABSTAIN);
        when(aclSubjectService.hasRules("kb_document_public", "READ")).thenReturn(true);

        IndexAclGuard guard = new IndexAclGuard(
                new IndexAliasResolver(),
                aclSubjectService,
                mock(ElasticsearchClient.class));

        String readable = guard.filterReadableScope(
                "kb_document_official,kb_document_public",
                identity);

        assertEquals("kb_document_official", readable);
    }

    @Test
    void readerWithoutMatchingRoleGetsNoReadableIndexWhenAllIndicesHaveRules() {
        IndexAclSubjectService aclSubjectService = mock(IndexAclSubjectService.class);
        JwtVerifier.UserIdentity identity = noReaderIdentity();
        when(aclSubjectService.decide("kb_document_official", identity, "READ"))
                .thenReturn(IndexAclSubjectService.Decision.ABSTAIN);
        when(aclSubjectService.hasRules("kb_document_official", "READ")).thenReturn(true);
        when(aclSubjectService.decide("kb_document_public", identity, "READ"))
                .thenReturn(IndexAclSubjectService.Decision.ABSTAIN);
        when(aclSubjectService.hasRules("kb_document_public", "READ")).thenReturn(true);

        IndexAclGuard guard = new IndexAclGuard(
                new IndexAliasResolver(),
                aclSubjectService,
                mock(ElasticsearchClient.class));

        String readable = guard.filterReadableScope(
                "kb_document_official,kb_document_public",
                identity);

        assertEquals("__no_readable_index__", readable);
    }

    @Test
    void strictDefaultDenyRejectsIndexWithoutRules() {
        IndexAclSubjectService aclSubjectService = mock(IndexAclSubjectService.class);
        JwtVerifier.UserIdentity identity = noReaderIdentity();
        when(aclSubjectService.decide("kb_document_law", identity, "READ"))
                .thenReturn(IndexAclSubjectService.Decision.ABSTAIN);
        when(aclSubjectService.hasRules("kb_document_law", "READ")).thenReturn(false);

        IndexAclGuard guard = new IndexAclGuard(
                new IndexAliasResolver(),
                aclSubjectService,
                mock(ElasticsearchClient.class));
        ReflectionTestUtils.setField(guard, "defaultDenyWhenNoRules", true);

        String readable = guard.filterReadableScope("kb_document_law", identity);

        assertEquals("__no_readable_index__", readable);
    }

    @Test
    void compatibilityModeAllowsIndexWithoutRules() {
        IndexAclSubjectService aclSubjectService = mock(IndexAclSubjectService.class);
        JwtVerifier.UserIdentity identity = noReaderIdentity();
        when(aclSubjectService.decide("kb_document_law", identity, "READ"))
                .thenReturn(IndexAclSubjectService.Decision.ABSTAIN);
        when(aclSubjectService.hasRules("kb_document_law", "READ")).thenReturn(false);

        IndexAclGuard guard = new IndexAclGuard(
                new IndexAliasResolver(),
                aclSubjectService,
                mock(ElasticsearchClient.class));

        String readable = guard.filterReadableScope("kb_document_law", identity);

        assertEquals("kb_document_law", readable);
    }

    /**
     * 构造超管身份，验证索引 ACL 层必须服从“管理员可查看全部文档”的业务规则。
     *
     * <p>这里使用匿名子类覆盖 {@code isSuperAdmin()}，避免为了测试构造完整 JWT Claims。</p>
     */
    private JwtVerifier.UserIdentity superAdminIdentity() {
        return new JwtVerifier.UserIdentity("admin", "000000", "ADMIN_MASTER_KEY", null) {
            @Override
            public boolean isSuperAdmin() {
                return true;
            }
        };
    }

    private JwtVerifier.UserIdentity officialReaderIdentity() {
        JwtVerifier.UserIdentity identity = new JwtVerifier.UserIdentity("u-official", "000000", "VEND_A_7788", null);
        identity.getAclTokens().add("role::official_reader");
        return identity;
    }

    private JwtVerifier.UserIdentity noReaderIdentity() {
        JwtVerifier.UserIdentity identity = new JwtVerifier.UserIdentity("u-none", "000000", "VEND_A_7788", null);
        identity.getAclTokens().add("role::no_reader");
        return identity;
    }
}
