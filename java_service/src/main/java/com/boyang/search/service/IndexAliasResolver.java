package com.boyang.search.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Canonicalizes ES index names used by the knowledge-base document indices.
 */
@Slf4j
@Service
public class IndexAliasResolver {

    public static final String DOCUMENT_READ_ALIAS = "kb_document";
    public static final String LEGACY_WILDCARD = "kb_document*";
    public static final String LEGACY_INDEX = "kb_document_v1";
    public static final String DOCUMENT_INDEX_PREFIX = "kb_document_";
    public static final String WRITE_ALIAS_SUFFIX = "_write";

    /**
     * 版本化物理索引名后缀正则（kb_document_official_v2 / _v3 ...）。
     * 仅用于把 ACL 表的存值/查值归一到"逻辑名"，让运行时查表与种子数据对齐。
     * 注意：ES 检索目标仍需用物理名（v2/v3），不要在此处之外的地方剥离本后缀。
     * 用正则而非固定字面量，让后续 _v3 重建等无需再改本常量；配合 toLogicalIndex 的守卫，
     * 既能剥 official_v2/v3，又不会误伤合法的 kb_document_v1（见 toLogicalIndex）。
     */
    public static final java.util.regex.Pattern VERSION_SUFFIX = java.util.regex.Pattern.compile("_(v\\d+)$");

    public String normalizeReadScope(String allowedScope) {
        List<String> scopes = splitScope(allowedScope);
        if (scopes.isEmpty()) {
            return DOCUMENT_READ_ALIAS;
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope.isEmpty()) {
                continue;
            }
            if (LEGACY_WILDCARD.equals(scope)) {
                normalized.add(DOCUMENT_READ_ALIAS);
                continue;
            }
            if (isDocumentWriteAlias(scope)) {
                normalized.add(toPhysicalIndex(scope));
                continue;
            }
            if (isDocumentPhysicalIndex(scope) || DOCUMENT_READ_ALIAS.equals(scope)) {
                normalized.add(scope);
            } else {
                log.warn("[IndexAliasResolver] ignored unsupported document read scope '{}'", scope);
            }
        }

        return normalized.isEmpty() ? DOCUMENT_READ_ALIAS : String.join(",", normalized);
    }

    public String normalizeWriteTarget(String targetIndex) {
        String target = trim(targetIndex);
        if (target.isEmpty()) {
            return LEGACY_INDEX;
        }
        if (isDocumentWriteAlias(target)) {
            return toPhysicalIndex(target);
        }
        if (isDocumentPhysicalIndex(target)) {
            return target;
        }
        throw new IllegalArgumentException("Unsupported document write target: " + targetIndex);
    }

    public boolean isAllowedByReadScope(String physicalIndex, String allowedScope) {
        String routed = trim(physicalIndex);
        if (!isDocumentPhysicalIndex(routed)) {
            return false;
        }
        String normalized = normalizeReadScope(allowedScope);
        for (String scope : splitScope(normalized)) {
            if (DOCUMENT_READ_ALIAS.equals(scope) || routed.equals(scope)) {
                return true;
            }
        }
        return false;
    }

    public boolean isDocumentPhysicalIndex(String indexName) {
        String name = trim(indexName);
        return name.startsWith(DOCUMENT_INDEX_PREFIX)
                && !name.contains("*")
                && !DOCUMENT_READ_ALIAS.equals(name)
                && !name.endsWith(WRITE_ALIAS_SUFFIX);
    }

    public boolean isDocumentWriteAlias(String indexName) {
        String name = trim(indexName);
        return name.startsWith(DOCUMENT_INDEX_PREFIX)
                && name.endsWith(WRITE_ALIAS_SUFFIX)
                && !name.contains("*");
    }

    public String toPhysicalIndex(String indexOrAlias) {
        String name = trim(indexOrAlias);
        if (isDocumentWriteAlias(name)) {
            return name.substring(0, name.length() - WRITE_ALIAS_SUFFIX.length());
        }
        return name;
    }

    /**
     * 把物理索引名归一为 ACL 用的"逻辑名"：剥离版本化后缀 _vN。
     * 例：kb_document_official_v2 / _v3 -> kb_document_official。
     * 守卫：剥离后前缀必须仍是 kb_document_* 分区名，且不能是裸读别名 kb_document——
     * 否则合法的 kb_document_v1 会被错剥成 kb_document（读别名），把 v1 的 ACL 规则错并到别名键上。
     */
    public String toLogicalIndex(String name) {
        String trimmed = trim(name);
        java.util.regex.Matcher m = VERSION_SUFFIX.matcher(trimmed);
        if (m.find()) {
            String prefix = trimmed.substring(0, m.start());
            if (prefix.startsWith(DOCUMENT_INDEX_PREFIX) && !prefix.equals(DOCUMENT_READ_ALIAS)) {
                return prefix;
            }
        }
        return trimmed;
    }

    public List<String> splitScope(String scope) {
        List<String> result = new ArrayList<>();
        if (scope == null || scope.trim().isEmpty()) {
            return result;
        }
        String[] parts = scope.split(",");
        for (String part : parts) {
            String item = trim(part);
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result;
    }

    private String trim(String value) {
        return value != null ? value.trim() : "";
    }
}
