package com.boyang.search.service;

import com.boyang.search.entity.KbIndexAclSubject;
import com.boyang.search.mapper.KbIndexAclSubjectMapper;
import com.boyang.search.security.JwtVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexAclSubjectServiceTest {

    @Mock
    private KbIndexAclSubjectMapper mapper;
    @Mock
    private PolicyVersionService policyVersionService;

    private IndexAclSubjectService service;

    @BeforeEach
    void setUp() {
        service = new IndexAclSubjectService(mapper, new IndexAliasResolver(), policyVersionService);
    }

    @Test
    void denyTakesPrecedenceOverAllow() {
        when(mapper.findActiveByIndexAndScope("kb_document_policy", "READ")).thenReturn(Arrays.asList(
                rule("ROLE", "editor", "READ", "ALLOW"),
                rule("USER", "u1", "READ", "DENY")
        ));

        assertEquals(IndexAclSubjectService.Decision.DENY,
                service.decide("kb_document_policy", identityWithRole("u1", "editor"), "READ"));
    }

    @Test
    void roleAllowMatchesIdentity() {
        when(mapper.findActiveByIndexAndScope("kb_document_policy", "READ")).thenReturn(Collections.singletonList(
                rule("ROLE", "editor", "READ", "ALLOW")
        ));

        assertEquals(IndexAclSubjectService.Decision.ALLOW,
                service.decide("kb_document_policy", identityWithRole("u1", "editor"), "READ"));
    }

    @Test
    void noRulesAbstainsForBackwardCompatibility() {
        when(mapper.findActiveByIndexAndScope("kb_document_policy", "READ")).thenReturn(Collections.emptyList());

        assertEquals(IndexAclSubjectService.Decision.ABSTAIN,
                service.decide("kb_document_policy", identityWithRole("u1", "editor"), "READ"));
    }

    /**
     * P1 回归守护：IndexAclGuard.expandReadAlias 传入的是 v2 物理名（kb_document_official_v2），
     * 必须归一到逻辑名 kb_document_official 后命中按逻辑名建的规则。
     * 迁移前这里会因查无规则而 ABSTAIN，配合 default-deny=false 静默放行（索引级 RBAC 打穿）。
     */
    @Test
    void decideCollapsesV2PhysicalNameToLogicalKey() {
        when(mapper.findActiveByIndexAndScope("kb_document_official", "READ")).thenReturn(Collections.singletonList(
                rule("ROLE", "editor", "READ", "ALLOW")));

        assertEquals(IndexAclSubjectService.Decision.ALLOW,
                service.decide("kb_document_official_v2", identityWithRole("u1", "editor"), "READ"));
    }

    @Test
    void hasRulesCollapsesV2PhysicalNameToLogicalKey() {
        when(mapper.findActiveByIndexAndScope("kb_document_official", "READ")).thenReturn(Collections.singletonList(
                rule("ROLE", "editor", "READ", "ALLOW")));

        assertTrue(service.hasRules("kb_document_official_v2", "READ"));
    }

    @Test
    void grantStoresLogicalKeyForV2PhysicalInput() {
        KbIndexAclSubject result = service.grant(
                "kb_document_official_v2", "ROLE", "editor", "READ", "ALLOW", "admin", null);

        assertEquals("kb_document_official", result.getIndexName());
    }

    private KbIndexAclSubject rule(String type, String value, String scope, String effect) {
        KbIndexAclSubject rule = new KbIndexAclSubject();
        rule.setIndexName("kb_document_policy");
        rule.setSubjectType(type);
        rule.setSubjectValue(value);
        rule.setScope(scope);
        rule.setEffect(effect);
        rule.setIsActive(1);
        return rule;
    }

    private JwtVerifier.UserIdentity identityWithRole(String userId, String role) {
        JwtVerifier.UserIdentity identity = new JwtVerifier.UserIdentity(userId, "D001", "app", null);
        identity.setAclTokens(new HashSet<>(Arrays.asList("role::" + role, "dept::D001")));
        return identity;
    }
}
