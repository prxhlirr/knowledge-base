package com.boyang.search.service;

import com.boyang.search.entity.SysTenantPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the smallest safe Elasticsearch read target for one search request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchIndexResolver {

    private static final String READ_ALIAS = "kb_document";
    private static final String LEGACY_WILDCARD = "kb_document*";
    private static final String LEGACY_INDEX = "kb_document_v1";
    private static final String DOCUMENT_INDEX_PREFIX = "kb_document_";

    private final DocIndexRoutingService docIndexRoutingService;

    public String resolve(SysTenantPolicy policy, Map<String, Object> filters) {
        String allowed = policy != null ? trim(policy.getIndexPattern()) : "";
        String routedIndex = resolveByFilter(filters);

        if (!routedIndex.isEmpty() && isAllowedByPolicy(routedIndex, allowed)) {
            return routedIndex;
        }
        if (!routedIndex.isEmpty()) {
            log.warn("[SearchIndexResolver] routed index '{}' denied by tenant allowedIndices='{}', fallback to allowed scope",
                    routedIndex, allowed);
        }

        return normalizeAllowedScope(allowed);
    }

    private String resolveByFilter(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }

        String explicitIndex = firstNonBlank(filters, "targetIndex", "target_index", "index");
        if (isDocumentPhysicalIndex(explicitIndex)) {
            return explicitIndex;
        }

        String routeKey = firstNonBlank(filters, "data_source", "tag", "doc_type");
        if (routeKey.isEmpty()) {
            return "";
        }

        String routed = trim(docIndexRoutingService.route(routeKey));
        return isDocumentPhysicalIndex(routed) ? routed : "";
    }

    private boolean isAllowedByPolicy(String routedIndex, String allowed) {
        if (allowed == null || allowed.trim().isEmpty()) {
            return true;
        }
        for (String token : splitScope(allowed)) {
            if (READ_ALIAS.equals(token) || LEGACY_WILDCARD.equals(token) || routedIndex.equals(token)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeAllowedScope(String allowed) {
        List<String> scopes = splitScope(allowed);
        if (scopes.isEmpty()) {
            return READ_ALIAS;
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope.isEmpty()) {
                continue;
            }
            if (LEGACY_WILDCARD.equals(scope)) {
                normalized.add(READ_ALIAS);
                continue;
            }
            if (isDocumentPhysicalIndex(scope) || READ_ALIAS.equals(scope) || LEGACY_INDEX.equals(scope)) {
                normalized.add(scope);
            } else {
                log.warn("[SearchIndexResolver] ignored unsafe or unsupported index scope '{}'", scope);
            }
        }

        if (normalized.isEmpty()) {
            return READ_ALIAS;
        }
        return String.join(",", normalized);
    }

    private List<String> splitScope(String scope) {
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

    private boolean isDocumentPhysicalIndex(String indexName) {
        return indexName != null
                && indexName.startsWith(DOCUMENT_INDEX_PREFIX)
                && !indexName.contains("*")
                && !READ_ALIAS.equals(indexName);
    }

    private String firstNonBlank(Map<String, Object> filters, String... keys) {
        for (String key : keys) {
            Object value = filters.get(key);
            String text = value != null ? value.toString().trim() : "";
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private String trim(String value) {
        return value != null ? value.trim() : "";
    }
}
