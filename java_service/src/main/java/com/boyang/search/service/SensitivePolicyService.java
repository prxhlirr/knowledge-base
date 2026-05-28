package com.boyang.search.service;

import com.boyang.search.entity.KbSensitivePolicy;
import com.boyang.search.mapper.KbSensitivePolicyMapper;
import com.boyang.search.security.JwtVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Slf4j
@Service
@RequiredArgsConstructor
public class SensitivePolicyService {

    private final KbSensitivePolicyMapper mapper;
    private final PolicyVersionService policyVersionService;
    private final SensitivePolicyAuditService auditService;

    private final Map<String, Pattern> regexCache = new ConcurrentHashMap<>();
    private final Map<String, LiteralPolicyMatcher> literalMatcherCache = new ConcurrentHashMap<>();

    @Value("${kb.sensitive.regex.max-pattern-length:512}")
    private int maxRegexPatternLength;

    @Value("${kb.sensitive.regex.max-input-length:20000}")
    private int maxRegexInputLength;

    @Value("${kb.sensitive.matcher.max-cache-size:64}")
    private int maxMatcherCacheSize;

    public KbSensitivePolicy createPolicy(KbSensitivePolicy policy, String operatorId) {
        if (policy == null || isBlank(policy.getPatternValue())) {
            throw new IllegalArgumentException("patternValue is required");
        }
        policy.setPatternType(defaultText(policy.getPatternType(), "WORD").toUpperCase(Locale.ROOT));
        policy.setAction(defaultText(policy.getAction(), "MASK").toUpperCase(Locale.ROOT));
        policy.setAppliesTo(defaultText(policy.getAppliesTo(), "SEARCH").toUpperCase(Locale.ROOT));
        policy.setSubjectType(defaultText(policy.getSubjectType(), "ALL").toUpperCase(Locale.ROOT));
        policy.setSubjectValue(defaultText(policy.getSubjectValue(), "*"));
        policy.setReplacement(defaultText(policy.getReplacement(), "[REDACTED]"));
        policy.setPriority(policy.getPriority() != null ? policy.getPriority() : 100);
        policy.setIsActive(policy.getIsActive() != null ? policy.getIsActive() : 1);
        policy.setCreatedBy(operatorId);
        policy.setCreatedAt(java.time.LocalDateTime.now());
        policy.setUpdatedAt(java.time.LocalDateTime.now());
        mapper.insert(policy);
        policyVersionService.bumpGlobalVersion("sensitive_policy_created");
        return policy;
    }

    public boolean disablePolicy(Long id) {
        KbSensitivePolicy policy = mapper.selectById(id);
        if (policy == null) {
            return false;
        }
        policy.setIsActive(0);
        policy.setUpdatedAt(java.time.LocalDateTime.now());
        mapper.updateById(policy);
        policyVersionService.bumpGlobalVersion("sensitive_policy_disabled");
        return true;
    }

    public KbSensitivePolicy updatePolicy(Long id, KbSensitivePolicy patch, String operatorId) {
        KbSensitivePolicy existing = mapper.selectById(id);
        if (existing == null) {
            return null;
        }
        if (patch == null) {
            throw new IllegalArgumentException("policy patch is required");
        }
        if (!isBlank(patch.getPatternType())) {
            existing.setPatternType(patch.getPatternType().trim().toUpperCase(Locale.ROOT));
        }
        if (!isBlank(patch.getPatternValue())) {
            existing.setPatternValue(patch.getPatternValue().trim());
        }
        if (!isBlank(patch.getAction())) {
            existing.setAction(patch.getAction().trim().toUpperCase(Locale.ROOT));
        }
        if (!isBlank(patch.getReplacement())) {
            existing.setReplacement(patch.getReplacement().trim());
        }
        if (!isBlank(patch.getAppliesTo())) {
            existing.setAppliesTo(patch.getAppliesTo().trim().toUpperCase(Locale.ROOT));
        }
        if (!isBlank(patch.getSubjectType())) {
            existing.setSubjectType(patch.getSubjectType().trim().toUpperCase(Locale.ROOT));
        }
        if (!isBlank(patch.getSubjectValue())) {
            existing.setSubjectValue(patch.getSubjectValue().trim());
        }
        if (patch.getPriority() != null) {
            existing.setPriority(patch.getPriority());
        }
        if (patch.getIsActive() != null) {
            existing.setIsActive(patch.getIsActive());
        }
        existing.setApprovedBy(operatorId);
        existing.setUpdatedAt(java.time.LocalDateTime.now());
        mapper.updateById(existing);
        policyVersionService.bumpGlobalVersion("sensitive_policy_updated");
        return existing;
    }

    public List<KbSensitivePolicy> listActive(String appliesTo) {
        return mapper.findActiveForStage(normalizeStage(appliesTo));
    }

    public FilterResult filterText(String text, String appliesTo, JwtVerifier.UserIdentity identity) {
        return filterText(text, appliesTo, identity, null, null, null);
    }

    public FilterResult filterText(String text,
                                   String appliesTo,
                                   JwtVerifier.UserIdentity identity,
                                   String fieldName,
                                   String sourceName,
                                   String docId) {
        if (text == null || text.isEmpty()) {
            return FilterResult.allowed(text, false);
        }
        String result = text;
        boolean changed = false;
        List<Long> matchedPolicyIds = new ArrayList<>();
        int totalHitCount = 0;
        String stage = normalizeStage(appliesTo);
        List<KbSensitivePolicy> policies = mapper.findActiveForStage(stage);
        LiteralPolicyMatcher literalMatcher = literalMatcher(stage, policies);
        Map<Long, Integer> literalCounts = literalMatcher.matchCounts(result);
        for (KbSensitivePolicy policy : policies) {
            if (!appliesToSubject(policy, identity)) {
                continue;
            }
            int hitCount = isRegexPolicy(policy)
                    ? regexHitCount(result, policy)
                    : literalCounts.getOrDefault(policy.getId(), 0);
            if (hitCount <= 0) {
                continue;
            }
            matchedPolicyIds.add(policy.getId());
            totalHitCount += hitCount;
            auditService.recordHit(policy, stage, identity, fieldName, sourceName, docId,
                    hitCount, MDC.get("traceId"));
            String action = normalize(policy.getAction(), "MASK");
            if ("BLOCK_DOC".equals(action) || "BLOCK_SNIPPET".equals(action) || "BLOCK_QA".equals(action)) {
                return FilterResult.blocked(policy, matchedPolicyIds, totalHitCount, action);
            }
            if ("MASK".equals(action)) {
                result = mask(result, policy);
                changed = true;
                literalCounts = literalMatcher.matchCounts(result);
            }
        }
        return FilterResult.allowed(result, changed, matchedPolicyIds, totalHitCount);
    }

    public boolean filterResultMap(Map<String, Object> item, String appliesTo, JwtVerifier.UserIdentity identity) {
        if (item == null) {
            return true;
        }
        List<String> fields = new ArrayList<>();
        fields.add("chunk_text");
        fields.add("snippet");
        fields.add("summary");
        fields.add("title");
        fields.add("file_name");
        fields.add("organization");
        fields.add("hit_text");
        fields.add("content");
        fields.add("sourceName");
        fields.add("docNumber");

        boolean blocked = false;
        boolean changed = false;
        for (String field : fields) {
            Object raw = item.get(field);
            if (!(raw instanceof String)) {
                continue;
            }
            FilterResult filtered = filterText((String) raw, appliesTo, identity,
                    field, firstNonBlank(item.get("file_name"), item.get("sourceName"), item.get("organization")),
                    firstNonBlank(item.get("doc_id"), item.get("_id")));
            if (filtered.isBlocked()) {
                blocked = true;
                break;
            }
            if (filtered.isChanged()) {
                item.put(field, filtered.getText());
                changed = true;
            }
        }
        if (changed) {
            item.put("sensitiveFiltered", true);
        }
        return !blocked;
    }

    private int regexHitCount(String text, KbSensitivePolicy policy) {
        String type = normalize(policy.getPatternType(), "WORD");
        String value = policy.getPatternValue();
        if (isBlank(value)) {
            return 0;
        }
        try {
            if ("REGEX".equals(type)) {
                Pattern pattern = compileSafeRegex(policy);
                if (pattern == null || text.length() > maxRegexInputLength) {
                    return 0;
                }
                java.util.regex.Matcher matcher = pattern.matcher(text);
                int count = 0;
                while (matcher.find()) {
                    count++;
                    if (count >= 1000) {
                        break;
                    }
                }
                return count;
            }
            return 0;
        } catch (PatternSyntaxException e) {
            log.warn("[SensitivePolicy] invalid regex policy id={} pattern={}", policy.getId(), value);
            return 0;
        }
    }

    private String mask(String text, KbSensitivePolicy policy) {
        String type = normalize(policy.getPatternType(), "WORD");
        String value = policy.getPatternValue();
        String replacement = defaultText(policy.getReplacement(), "[REDACTED]");
        if ("REGEX".equals(type)) {
            Pattern pattern = compileSafeRegex(policy);
            if (pattern == null || text.length() > maxRegexInputLength) {
                return text;
            }
            return pattern.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(replacement));
        }
        return text.replace(value, replacement);
    }

    private Pattern compileSafeRegex(KbSensitivePolicy policy) {
        String value = policy.getPatternValue();
        if (value == null || value.length() > maxRegexPatternLength || looksCatastrophicRegex(value)) {
            log.warn("[SensitivePolicy] unsafe regex skipped policyId={} length={}",
                    policy.getId(), value == null ? 0 : value.length());
            return null;
        }
        String cacheKey = policy.getId() + ":" + value;
        return regexCache.computeIfAbsent(cacheKey, key -> Pattern.compile(value));
    }

    private boolean looksCatastrophicRegex(String pattern) {
        String compact = pattern.replaceAll("\\s+", "");
        return compact.matches(".*\\([^)]*[+*][^)]*\\)[+*].*")
                || compact.contains(".*.*")
                || compact.contains("(.+)+")
                || compact.contains("(.*)+");
    }

    private int countLiteral(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
            if (count >= 1000) {
                break;
            }
        }
        return count;
    }

    private LiteralPolicyMatcher literalMatcher(String stage, List<KbSensitivePolicy> policies) {
        String key = "literal:" + stage + ":v" + policyVersionService.currentGlobalVersion() + ":" + literalSignature(policies);
        if (literalMatcherCache.size() > maxMatcherCacheSize) {
            literalMatcherCache.clear();
        }
        return literalMatcherCache.computeIfAbsent(key, ignored -> LiteralPolicyMatcher.build(policies));
    }

    private String literalSignature(List<KbSensitivePolicy> policies) {
        if (policies == null || policies.isEmpty()) {
            return "empty";
        }
        StringBuilder signature = new StringBuilder();
        for (KbSensitivePolicy policy : policies) {
            if (!isLiteralPolicy(policy)) {
                continue;
            }
            signature.append(policy.getId()).append('@')
                    .append(policy.getPatternValue() == null ? 0 : policy.getPatternValue().hashCode())
                    .append(';');
        }
        return signature.length() == 0 ? "empty" : Integer.toHexString(signature.toString().hashCode());
    }

    private boolean isRegexPolicy(KbSensitivePolicy policy) {
        return policy != null && "REGEX".equals(normalize(policy.getPatternType(), "WORD"));
    }

    private boolean isLiteralPolicy(KbSensitivePolicy policy) {
        return policy != null && !isRegexPolicy(policy) && !isBlank(policy.getPatternValue());
    }

    private String firstNonBlank(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    private boolean appliesToSubject(KbSensitivePolicy policy, JwtVerifier.UserIdentity identity) {
        String type = normalize(policy.getSubjectType(), "ALL");
        String value = defaultText(policy.getSubjectValue(), "*");
        if ("ALL".equals(type) || "*".equals(value)) {
            return true;
        }
        if (identity == null) {
            return false;
        }
        if ("USER".equals(type)) {
            return value.equals(identity.getUserId());
        }
        if ("ROLE".equals(type)) {
            return (identity.getRoles() != null && identity.getRoles().contains(value))
                    || (identity.getAclTokens() != null && identity.getAclTokens().contains("role::" + value));
        }
        if ("DEPT".equals(type)) {
            return identity.getAclTokens() != null
                    && identity.getAclTokens().contains("dept::" + DeptTreeService.normalizeDeptCode(value));
        }
        return false;
    }

    private String normalizeStage(String appliesTo) {
        return normalize(appliesTo, "SEARCH");
    }

    private String normalize(String value, String fallback) {
        return value != null && !value.trim().isEmpty()
                ? value.trim().toUpperCase(Locale.ROOT)
                : fallback;
    }

    private String defaultText(String value) {
        return defaultText(value, null);
    }

    private String defaultText(String value, String fallback) {
        return value != null && !value.trim().isEmpty() ? value.trim() : fallback;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class LiteralPolicyMatcher {
        private final Node root;

        private LiteralPolicyMatcher(Node root) {
            this.root = root;
        }

        static LiteralPolicyMatcher build(List<KbSensitivePolicy> policies) {
            Node root = new Node();
            if (policies != null) {
                for (KbSensitivePolicy policy : policies) {
                    if (policy == null || policy.getId() == null || policy.getPatternValue() == null
                            || policy.getPatternValue().trim().isEmpty()
                            || "REGEX".equalsIgnoreCase(policy.getPatternType())) {
                        continue;
                    }
                    Node node = root;
                    String word = policy.getPatternValue();
                    for (int i = 0; i < word.length(); i++) {
                        char ch = word.charAt(i);
                        Node next = node.children.get(ch);
                        if (next == null) {
                            next = new Node();
                            node.children.put(ch, next);
                        }
                        node = next;
                    }
                    node.policyIds.add(policy.getId());
                }
            }
            buildFailureLinks(root);
            return new LiteralPolicyMatcher(root);
        }

        Map<Long, Integer> matchCounts(String text) {
            if (text == null || text.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<Long, Integer> counts = new HashMap<>();
            Node node = root;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                while (node != root && !node.children.containsKey(ch)) {
                    node = node.fail;
                }
                Node next = node.children.get(ch);
                node = next != null ? next : root;
                if (!node.policyIds.isEmpty()) {
                    for (Long policyId : node.policyIds) {
                        Integer current = counts.get(policyId);
                        counts.put(policyId, current == null ? 1 : Math.min(1000, current + 1));
                    }
                }
            }
            return counts;
        }

        private static void buildFailureLinks(Node root) {
            Queue<Node> queue = new ArrayDeque<>();
            root.fail = root;
            for (Node child : root.children.values()) {
                child.fail = root;
                queue.add(child);
            }
            while (!queue.isEmpty()) {
                Node current = queue.poll();
                for (Map.Entry<Character, Node> entry : current.children.entrySet()) {
                    char ch = entry.getKey();
                    Node child = entry.getValue();
                    Node fail = current.fail;
                    while (fail != root && !fail.children.containsKey(ch)) {
                        fail = fail.fail;
                    }
                    Node fallback = fail.children.get(ch);
                    child.fail = fallback != null && fallback != child ? fallback : root;
                    child.policyIds.addAll(child.fail.policyIds);
                    queue.add(child);
                }
            }
        }

        private static class Node {
            final Map<Character, Node> children = new HashMap<>();
            final List<Long> policyIds = new ArrayList<>();
            Node fail;
        }
    }

    public static class FilterResult {
        private final boolean blocked;
        private final boolean changed;
        private final String text;
        private final KbSensitivePolicy policy;
        private final List<Long> matchedPolicyIds;
        private final int hitCount;
        private final String action;

        private FilterResult(boolean blocked,
                             boolean changed,
                             String text,
                             KbSensitivePolicy policy,
                             List<Long> matchedPolicyIds,
                             int hitCount,
                             String action) {
            this.blocked = blocked;
            this.changed = changed;
            this.text = text;
            this.policy = policy;
            this.matchedPolicyIds = matchedPolicyIds == null
                    ? java.util.Collections.emptyList()
                    : java.util.Collections.unmodifiableList(new ArrayList<>(matchedPolicyIds));
            this.hitCount = hitCount;
            this.action = action;
        }

        public static FilterResult allowed(String text, boolean changed) {
            return allowed(text, changed, java.util.Collections.emptyList(), 0);
        }

        public static FilterResult allowed(String text, boolean changed, List<Long> matchedPolicyIds, int hitCount) {
            return new FilterResult(false, changed, text, null, matchedPolicyIds, hitCount, changed ? "MASK" : null);
        }

        public static FilterResult blocked(KbSensitivePolicy policy) {
            return blocked(policy, java.util.Collections.singletonList(policy.getId()), 1, policy.getAction());
        }

        public static FilterResult blocked(KbSensitivePolicy policy,
                                           List<Long> matchedPolicyIds,
                                           int hitCount,
                                           String action) {
            return new FilterResult(true, false, null, policy, matchedPolicyIds, hitCount, action);
        }

        public boolean isBlocked() { return blocked; }
        public boolean isChanged() { return changed; }
        public String getText() { return text; }
        public KbSensitivePolicy getPolicy() { return policy; }
        public List<Long> getMatchedPolicyIds() { return matchedPolicyIds; }
        public int getHitCount() { return hitCount; }
        public String getAction() { return action; }
    }
}
