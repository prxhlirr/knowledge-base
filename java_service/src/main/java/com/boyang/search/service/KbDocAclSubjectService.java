package com.boyang.search.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.boyang.search.entity.KbDocAclSubject;
import com.boyang.search.entity.KbDocRegistry;
import com.boyang.search.mapper.KbDocAclSubjectMapper;
import com.boyang.search.security.JwtVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class KbDocAclSubjectService {

    private final KbDocAclSubjectMapper mapper;
    private final DeptTreeService deptTreeService;
    private final DocAclProjectionService projectionService;
    private final PolicyVersionService policyVersionService;

    public enum Decision {
        ALLOW, DENY, ABSTAIN
    }

    @Transactional
    public void replaceInitialSubjects(KbDocRegistry doc,
                                       List<String> grantedUsers,
                                       List<String> grantedRoles,
                                       String createdBy) {
        if (doc == null || doc.getSourceName() == null) {
            return;
        }

        KbDocAclSubject inactive = new KbDocAclSubject();
        inactive.setIsActive(0);
        inactive.setUpdatedAt(LocalDateTime.now());

        mapper.update(inactive, new LambdaUpdateWrapper<KbDocAclSubject>()
                .eq(KbDocAclSubject::getSourceName, doc.getSourceName())
                .eq(KbDocAclSubject::getSourceType, "INGEST_INIT")
                .eq(KbDocAclSubject::getIsActive, 1));

        List<KbDocAclSubject> rows = buildInitialSubjects(doc, grantedUsers, grantedRoles, createdBy);
        for (KbDocAclSubject row : rows) {
            mapper.insert(row);
        }
        policyVersionService.bumpGlobalVersion("doc_acl_initial_subjects_replaced");
        log.info("[DocACL] initial ACL subjects synced sourceName={} version={} count={}",
                doc.getSourceName(), doc.getDocVersion(), rows.size());
    }

    @Transactional
    public void grantRuntimeUser(Long registryId, String sourceName, String userId, String grantedBy, LocalDateTime expiresAt) {
        KbDocRegistry doc = new KbDocRegistry();
        doc.setId(registryId);
        doc.setSourceName(sourceName);
        grantRuntimeSubject(doc, "USER", userId, "VIEW", "ALLOW", grantedBy, expiresAt, false);
    }

    @Transactional
    public void revokeRuntimeUser(String sourceName, String userId) {
        revokeRuntimeSubject(sourceName, null, "USER", userId, "VIEW", "ALLOW", false);
    }

    @Transactional
    public KbDocAclSubject grantRuntimeSubject(KbDocRegistry doc,
                                               String subjectType,
                                               String subjectValue,
                                               String scope,
                                               String effect,
                                               String createdBy,
                                               LocalDateTime expiresAt,
                                               boolean projectEs) {
        if (doc == null || !notBlank(doc.getSourceName()) || !notBlank(subjectType) || !notBlank(subjectValue)) {
            throw new IllegalArgumentException("sourceName, subjectType and subjectValue are required");
        }
        String normalizedType = normalize(subjectType);
        String normalizedValue = normalizeSubjectValue(normalizedType, subjectValue);
        String normalizedScope = normalizeScope(scope);
        String normalizedEffect = normalizeEffect(effect);
        KbDocAclSubject row = new KbDocAclSubject();
        row.setRegistryId(doc.getId());
        row.setSourceName(doc.getSourceName());
        row.setDocVersion(doc.getDocVersion() != null ? doc.getDocVersion() : 0);
        row.setSubjectType(normalizedType);
        row.setSubjectValue(normalizedValue);
        row.setScope(normalizedScope);
        row.setEffect(normalizedEffect);
        row.setSourceType("RUNTIME_GRANT");
        row.setExpiresAt(expiresAt);
        row.setIsActive(1);
        row.setCreatedBy(createdBy);
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        mapper.insert(row);

        if (projectEs && "ALLOW".equals(normalizedEffect)) {
            String token = toAclToken(normalizedType, normalizedValue);
            if (notBlank(token)) {
                projectionService.grantToken(doc.getTargetIndex(), doc.getSourceName(), token);
            }
        }
        policyVersionService.bumpGlobalVersion("doc_acl_subject_granted");
        return row;
    }

    @Transactional
    public void revokeRuntimeSubject(String sourceName,
                                     String targetIndex,
                                     String subjectType,
                                     String subjectValue,
                                     String scope,
                                     String effect,
                                     boolean projectEs) {
        if (!notBlank(sourceName) || !notBlank(subjectType) || !notBlank(subjectValue)) {
            throw new IllegalArgumentException("sourceName, subjectType and subjectValue are required");
        }
        String normalizedType = normalize(subjectType);
        String normalizedValue = normalizeSubjectValue(normalizedType, subjectValue);
        String normalizedScope = normalizeScope(scope);
        String normalizedEffect = normalizeEffect(effect);
        KbDocAclSubject inactive = new KbDocAclSubject();
        inactive.setIsActive(0);
        inactive.setUpdatedAt(LocalDateTime.now());
        mapper.update(inactive, new LambdaUpdateWrapper<KbDocAclSubject>()
                .eq(KbDocAclSubject::getSourceName, sourceName)
                .eq(KbDocAclSubject::getSubjectType, normalizedType)
                .eq(KbDocAclSubject::getSubjectValue, normalizedValue)
                .eq(KbDocAclSubject::getScope, normalizedScope)
                .eq(KbDocAclSubject::getEffect, normalizedEffect)
                .eq(KbDocAclSubject::getSourceType, "RUNTIME_GRANT")
                .eq(KbDocAclSubject::getIsActive, 1));

        if (projectEs && "ALLOW".equals(normalizedEffect)
                && mapper.countActiveSubject(sourceName, normalizedType, normalizedValue, normalizedScope, "ALLOW") == 0) {
            String token = toAclToken(normalizedType, normalizedValue);
            if (notBlank(token)) {
                projectionService.revokeToken(targetIndex, sourceName, token);
            }
        }
        policyVersionService.bumpGlobalVersion("doc_acl_subject_revoked");
    }

    public List<KbDocAclSubject> listActive(String sourceName) {
        if (!notBlank(sourceName)) {
            return java.util.Collections.emptyList();
        }
        return mapper.findActiveBySource(sourceName);
    }

    public Decision decide(KbDocRegistry doc, JwtVerifier.UserIdentity identity, String scope) {
        if (doc == null || doc.getSourceName() == null) {
            return Decision.ABSTAIN;
        }
        List<KbDocAclSubject> subjects = mapper.findActiveBySourceAndScope(doc.getSourceName(), normalizeScope(scope));
        if (subjects == null || subjects.isEmpty()) {
            return Decision.ABSTAIN;
        }

        Set<String> principals = buildPrincipalKeys(identity);
        boolean allow = false;
        for (KbDocAclSubject subject : subjects) {
            String key = principalKey(subject.getSubjectType(), subject.getSubjectValue());
            if (!principals.contains(key)) {
                continue;
            }
            if ("DENY".equalsIgnoreCase(subject.getEffect())) {
                return Decision.DENY;
            }
            if ("ALLOW".equalsIgnoreCase(subject.getEffect())) {
                allow = true;
            }
        }
        return allow ? Decision.ALLOW : Decision.ABSTAIN;
    }

    private List<KbDocAclSubject> buildInitialSubjects(KbDocRegistry doc,
                                                       List<String> grantedUsers,
                                                       List<String> grantedRoles,
                                                       String createdBy) {
        List<KbDocAclSubject> rows = new ArrayList<>();
        String visibility = doc.getVisibility() != null
                ? doc.getVisibility().toUpperCase(Locale.ROOT)
                : "INTERNAL";
        switch (visibility) {
            case "PUBLIC":
                rows.add(row(doc, "ALL", "*", "VIEW", "ALLOW", createdBy));
                break;
            case "INTERNAL":
                rows.add(row(doc, "AUTHENTICATED", "*", "VIEW", "ALLOW", createdBy));
                break;
            case "DEPT":
                addDeptRows(rows, doc, doc.getDeptCode(), createdBy);
                addUploaderRow(rows, doc, createdBy);
                break;
            case "PRIVATE":
                addUploaderRow(rows, doc, createdBy);
                break;
            case "GRANT":
                addUploaderRow(rows, doc, createdBy);
                addUserRows(rows, doc, grantedUsers, createdBy);
                addRoleRows(rows, doc, grantedRoles, createdBy);
                break;
            default:
                addUploaderRow(rows, doc, createdBy);
                break;
        }
        return rows;
    }

    private void addUploaderRow(List<KbDocAclSubject> rows, KbDocRegistry doc, String createdBy) {
        if (notBlank(doc.getUploaderId())) {
            rows.add(row(doc, "USER", doc.getUploaderId(), "VIEW", "ALLOW", createdBy));
        }
    }

    private void addUserRows(List<KbDocAclSubject> rows, KbDocRegistry doc, List<String> userIds, String createdBy) {
        if (userIds == null) {
            return;
        }
        for (String userId : userIds) {
            if (notBlank(userId)) {
                rows.add(row(doc, "USER", userId.trim(), "VIEW", "ALLOW", createdBy));
            }
        }
    }

    private void addRoleRows(List<KbDocAclSubject> rows, KbDocRegistry doc, List<String> roles, String createdBy) {
        if (roles == null) {
            return;
        }
        for (String role : roles) {
            if (notBlank(role)) {
                rows.add(row(doc, "ROLE", role.trim(), "VIEW", "ALLOW", createdBy));
            }
        }
    }

    private void addDeptRows(List<KbDocAclSubject> rows, KbDocRegistry doc, String deptCode, String createdBy) {
        if (!notBlank(deptCode)) {
            return;
        }
        List<String> chain = deptTreeService.buildAclChain(deptCode);
        for (String code : chain) {
            if (notBlank(code)) {
                rows.add(row(doc, "DEPT", code.trim(), "VIEW", "ALLOW", createdBy));
            }
        }
    }

    private KbDocAclSubject row(KbDocRegistry doc, String subjectType, String subjectValue,
                                String scope, String effect, String createdBy) {
        KbDocAclSubject row = new KbDocAclSubject();
        row.setRegistryId(doc.getId());
        row.setSourceName(doc.getSourceName());
        row.setDocVersion(doc.getDocVersion());
        row.setSubjectType(subjectType);
        row.setSubjectValue(subjectValue);
        row.setScope(normalizeScope(scope));
        row.setEffect(effect);
        row.setSourceType("INGEST_INIT");
        row.setIsActive(1);
        row.setCreatedBy(createdBy);
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        return row;
    }

    private Set<String> buildPrincipalKeys(JwtVerifier.UserIdentity identity) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(principalKey("ALL", "*"));
        if (identity == null || !notBlank(identity.getUserId())) {
            return keys;
        }
        keys.add(principalKey("AUTHENTICATED", "*"));
        keys.add(principalKey("USER", identity.getUserId()));

        for (String role : identity.getRoles()) {
            if (notBlank(role)) {
                keys.add(principalKey("ROLE", role));
            }
        }
        for (String token : identity.getAclTokens()) {
            if (!notBlank(token)) {
                continue;
            }
            if (token.startsWith("role::")) {
                keys.add(principalKey("ROLE", token.substring("role::".length())));
            } else if (token.startsWith("dept::")) {
                keys.add(principalKey("DEPT", token.substring("dept::".length())));
            } else if (token.startsWith("user::")) {
                keys.add(principalKey("USER", token.substring("user::".length())));
            } else if ("_PUBLIC".equals(token)) {
                keys.add(principalKey("ALL", "*"));
            } else if ("_INTERNAL".equals(token)) {
                keys.add(principalKey("AUTHENTICATED", "*"));
            }
        }
        return keys;
    }

    private String principalKey(String subjectType, String subjectValue) {
        return normalize(subjectType) + ":" + (subjectValue != null ? subjectValue.trim() : "");
    }

    private String normalizeScope(String scope) {
        return scope != null && !scope.trim().isEmpty()
                ? scope.trim().toUpperCase(Locale.ROOT)
                : "VIEW";
    }

    private String normalize(String value) {
        return value != null ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String normalizeEffect(String effect) {
        String normalized = effect != null && !effect.trim().isEmpty()
                ? effect.trim().toUpperCase(Locale.ROOT)
                : "ALLOW";
        if (!"ALLOW".equals(normalized) && !"DENY".equals(normalized)) {
            throw new IllegalArgumentException("effect must be ALLOW or DENY");
        }
        return normalized;
    }

    public String toAclToken(String subjectType, String subjectValue) {
        String type = normalize(subjectType);
        if (!notBlank(subjectValue)) {
            return null;
        }
        String value = subjectValue.trim();
        switch (type) {
            case "ALL":
                return "_PUBLIC";
            case "AUTHENTICATED":
                return "_INTERNAL";
            case "USER":
                return "user::" + value;
            case "ROLE":
                return "role::" + value;
            case "DEPT":
                return "dept::" + value;
            default:
                return null;
        }
    }

    private String normalizeSubjectValue(String subjectType, String subjectValue) {
        String value = subjectValue != null ? subjectValue.trim() : "";
        if ("DEPT".equals(subjectType)) {
            return DeptTreeService.normalizeDeptCode(value);
        }
        return value;
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
