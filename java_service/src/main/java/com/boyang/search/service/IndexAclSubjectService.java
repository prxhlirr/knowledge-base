package com.boyang.search.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.boyang.search.entity.KbIndexAclSubject;
import com.boyang.search.mapper.KbIndexAclSubjectMapper;
import com.boyang.search.security.JwtVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 索引级权限服务。
 *
 * <p>租户策略决定“这个 appCode 最大能访问哪些索引”，索引 ACL 决定“当前用户/角色/部门在这些索引内还能看哪些”。
 * DENY 优先于 ALLOW；若某个索引没有配置任何 ACL，则默认不额外限制，兼容现有租户级模型。</p>
 */
@Service
@RequiredArgsConstructor
public class IndexAclSubjectService {

    private final KbIndexAclSubjectMapper mapper;
    private final IndexAliasResolver indexAliasResolver;
    private final PolicyVersionService policyVersionService;

    public enum Decision {
        ALLOW, DENY, ABSTAIN
    }

    @Transactional
    public KbIndexAclSubject grant(String indexName,
                                   String subjectType,
                                   String subjectValue,
                                   String scope,
                                   String effect,
                                   String createdBy,
                                   LocalDateTime expiresAt) {
        String aclKey = indexAliasResolver.toLogicalIndex(indexAliasResolver.normalizeWriteTarget(indexName));
        KbIndexAclSubject row = new KbIndexAclSubject();
        row.setIndexName(aclKey);
        row.setReadAlias(IndexAliasResolver.DOCUMENT_READ_ALIAS);
        row.setSubjectType(normalize(subjectType));
        row.setSubjectValue(normalizeSubjectValue(row.getSubjectType(), subjectValue));
        row.setScope(normalizeScope(scope));
        row.setEffect(normalizeEffect(effect));
        row.setExpiresAt(expiresAt);
        row.setIsActive(1);
        row.setCreatedBy(createdBy);
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        mapper.insert(row);
        policyVersionService.bumpGlobalVersion("index_acl_subject_granted");
        return row;
    }

    @Transactional
    public void revoke(Long id) {
        KbIndexAclSubject row = mapper.selectById(id);
        if (row == null) {
            return;
        }
        row.setIsActive(0);
        row.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(row);
        policyVersionService.bumpGlobalVersion("index_acl_subject_revoked");
    }

    public List<KbIndexAclSubject> listActive(String indexName) {
        String aclKey = indexAliasResolver.toLogicalIndex(indexAliasResolver.normalizeWriteTarget(indexName));
        return mapper.findActiveByIndex(aclKey);
    }

    public Decision decide(String indexName, JwtVerifier.UserIdentity identity, String scope) {
        String aclKey = indexAliasResolver.toLogicalIndex(indexAliasResolver.normalizeWriteTarget(indexName));
        List<KbIndexAclSubject> rules = mapper.findActiveByIndexAndScope(aclKey, normalizeScope(scope));
        if (rules == null || rules.isEmpty()) {
            return Decision.ABSTAIN;
        }
        Set<String> principals = principalKeys(identity);
        boolean allow = false;
        for (KbIndexAclSubject rule : rules) {
            String key = principalKey(rule.getSubjectType(), rule.getSubjectValue());
            if (!principals.contains(key)) {
                continue;
            }
            if ("DENY".equalsIgnoreCase(rule.getEffect())) {
                return Decision.DENY;
            }
            if ("ALLOW".equalsIgnoreCase(rule.getEffect())) {
                allow = true;
            }
        }
        return allow ? Decision.ALLOW : Decision.ABSTAIN;
    }

    public boolean hasRules(String indexName, String scope) {
        String aclKey = indexAliasResolver.toLogicalIndex(indexAliasResolver.normalizeWriteTarget(indexName));
        List<KbIndexAclSubject> rules = mapper.findActiveByIndexAndScope(aclKey, normalizeScope(scope));
        return rules != null && !rules.isEmpty();
    }

    private Set<String> principalKeys(JwtVerifier.UserIdentity identity) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add("ALL::*");
        if (identity == null || identity.getUserId() == null || identity.getUserId().trim().isEmpty()) {
            return keys;
        }
        keys.add("AUTHENTICATED::*");
        keys.add("USER::" + identity.getUserId());
        if (identity.getRoles() != null) {
            for (String role : identity.getRoles()) {
                if (role != null && !role.trim().isEmpty()) {
                    keys.add("ROLE::" + role.trim());
                }
            }
        }
        if (identity.getAclTokens() != null) {
            for (String token : identity.getAclTokens()) {
                if (token != null && token.startsWith("role::")) {
                    keys.add("ROLE::" + token.substring("role::".length()));
                }
                if (token != null && token.startsWith("dept::")) {
                    keys.add("DEPT::" + token.substring("dept::".length()));
                }
            }
        }
        return keys;
    }

    private String principalKey(String type, String value) {
        return normalize(type) + "::" + (value == null ? "*" : value.trim());
    }

    private String normalizeSubjectValue(String type, String value) {
        if (value == null || value.trim().isEmpty()) {
            return "*";
        }
        if ("DEPT".equals(type)) {
            return DeptTreeService.normalizeDeptCode(value);
        }
        return value.trim();
    }

    private String normalizeScope(String scope) {
        return scope == null || scope.trim().isEmpty() ? "READ" : scope.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeEffect(String effect) {
        return "DENY".equalsIgnoreCase(effect) ? "DENY" : "ALLOW";
    }

    private String normalize(String value) {
        return value == null || value.trim().isEmpty() ? "ALL" : value.trim().toUpperCase(Locale.ROOT);
    }
}
