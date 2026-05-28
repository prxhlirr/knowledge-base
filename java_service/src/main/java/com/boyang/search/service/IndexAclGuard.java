package com.boyang.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import com.boyang.search.security.JwtVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 索引级访问守卫。
 *
 * <p>该组件只负责在租户允许的索引范围内继续做用户/角色/部门级过滤。
 * 如果某个索引没有配置索引 ACL，则兼容旧模型，默认允许租户范围内访问。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexAclGuard {

    private final IndexAliasResolver indexAliasResolver;
    private final IndexAclSubjectService indexAclSubjectService;
    private final ElasticsearchClient esClient;

    public String filterReadableScope(String scope, JwtVerifier.UserIdentity identity) {
        String normalized = indexAliasResolver.normalizeReadScope(scope);
        List<String> allowed = new ArrayList<>();
        for (String item : indexAliasResolver.splitScope(normalized)) {
            if (IndexAliasResolver.DOCUMENT_READ_ALIAS.equals(item)) {
                allowed.addAll(filterPhysicalIndices(expandReadAlias(item), identity));
                continue;
            }
            if (!indexAliasResolver.isDocumentPhysicalIndex(item)) {
                continue;
            }
            if (isReadable(item, identity)) {
                allowed.add(item);
            }
        }
        return allowed.isEmpty() ? "__no_readable_index__" : String.join(",", allowed);
    }

    private List<String> filterPhysicalIndices(List<String> indices, JwtVerifier.UserIdentity identity) {
        List<String> allowed = new ArrayList<>();
        for (String index : indices) {
            if (isReadable(index, identity)) {
                allowed.add(index);
            }
        }
        return allowed;
    }

    private boolean isReadable(String indexName, JwtVerifier.UserIdentity identity) {
        if (!indexAliasResolver.isDocumentPhysicalIndex(indexName)) {
            return false;
        }
        IndexAclSubjectService.Decision decision = indexAclSubjectService.decide(indexName, identity, "READ");
        if (decision == IndexAclSubjectService.Decision.DENY) {
            return false;
        }
        return decision == IndexAclSubjectService.Decision.ALLOW
                || !indexAclSubjectService.hasRules(indexName, "READ");
    }

    private List<String> expandReadAlias(String aliasName) {
        List<String> indices = new ArrayList<>();
        try {
            GetAliasResponse response = esClient.indices().getAlias(g -> g.name(aliasName));
            for (Map.Entry<String, co.elastic.clients.elasticsearch.indices.get_alias.IndexAliases> entry
                    : response.result().entrySet()) {
                if (indexAliasResolver.isDocumentPhysicalIndex(entry.getKey())) {
                    indices.add(entry.getKey());
                }
            }
        } catch (Exception e) {
            log.warn("[IndexAclGuard] expand read alias failed alias={} err={}", aliasName, e.getMessage());
        }
        if (indices.isEmpty()) {
            log.warn("[IndexAclGuard] read alias '{}' resolved to no physical indices", aliasName);
        }
        return indices;
    }
}
