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
