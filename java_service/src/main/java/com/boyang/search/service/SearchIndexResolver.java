package com.boyang.search.service;

import com.boyang.search.entity.SysTenantPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Resolves the smallest safe Elasticsearch read target for one search request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchIndexResolver {

    private final DocIndexRoutingService docIndexRoutingService;
    private final IndexAliasResolver indexAliasResolver;
    private final IndexAclGuard indexAclGuard;

    public String resolve(SysTenantPolicy policy, Map<String, Object> filters) {
        String allowed = policy != null ? trim(policy.getIndexPattern()) : "";
        String routedIndex = resolveByFilter(filters);

        if (!routedIndex.isEmpty() && indexAliasResolver.isAllowedByReadScope(routedIndex, allowed)) {
            return indexAclGuard.filterReadableScope(routedIndex,
                    com.boyang.search.security.UserContextHolder.getIdentity());
        }
        if (!routedIndex.isEmpty()) {
            log.warn("[SearchIndexResolver] routed index '{}' denied by tenant allowedIndices='{}', fallback to allowed scope",
                    routedIndex, allowed);
        }

        return indexAclGuard.filterReadableScope(allowed,
                com.boyang.search.security.UserContextHolder.getIdentity());
    }

    private String resolveByFilter(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }

        String explicitIndex = firstNonBlank(filters, "targetIndex", "target_index", "index");
        try {
            if (!explicitIndex.isEmpty()) {
                return indexAliasResolver.normalizeWriteTarget(explicitIndex);
            }
        } catch (IllegalArgumentException ignored) {
            log.warn("[SearchIndexResolver] ignored unsafe explicit index filter '{}'", explicitIndex);
        }

        String routeKey = firstNonBlank(filters, "data_source", "tag", "doc_type");
        if (routeKey.isEmpty()) {
            return "";
        }

        String routed = trim(docIndexRoutingService.route(routeKey));
        return indexAliasResolver.isDocumentPhysicalIndex(routed) ? routed : "";
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
