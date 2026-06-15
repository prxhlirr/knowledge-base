package com.boyang.search.util;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * [P1 优化⑧] 搜索查询级轻量缓存（进程内，TTL 自动过期）。
 *
 * 设计目标：
 *   - 缓存高频重复查询的向量、稀疏向量、prefilter 结果，避免重复 HTTP/ES 调用
 *   - TTL 机制保证数据新鲜度（向量 5min，prefilter 60s）
 *   - 有界缓存（最大容量 500 条），LRU 驱逐防止内存泄漏
 *   - 线程安全（ConcurrentHashMap + volatile）
 *
 * 缓存粒度：
 *   - denseVectorCache : key = queryText, value = BGE-M3 dense vector（TTL 5min）
 *   - sparseVectorCache: key = queryText, value = sparse vector map（TTL 5min）
 *   - prefilterCache   : key = queryText + "|" + userId, value = candidate sources（TTL 60s）
 *
 * 注意：
 *   - 本缓存不缓存 ColBERT 结果（query + doc 组合复杂度高，收益有限）
 *   - 缓存命中率可通过 getStats() 监控，辅助容量规划
 */
@Component
public class SearchQueryCache {

    /** 缓存条目，带 TTL 过期时间戳 */
    private static class CacheEntry<V> {
        final V value;
        final long expireAtMs;

        CacheEntry(V value, long ttlMs) {
            this.value = value;
            this.expireAtMs = System.currentTimeMillis() + ttlMs;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAtMs;
        }
    }

    private final ConcurrentHashMap<String, CacheEntry<List<Double>>> denseVectorCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<Map<String, Double>>> sparseVectorCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<List<?>>> prefilterCache = new ConcurrentHashMap<>();

    // 缓存容量上限（每种缓存类型）
    private static final int MAX_CACHE_SIZE = 500;
    // TTL 配置（毫秒）
    private static final long VECTOR_TTL_MS = 5 * 60 * 1000L;    // 5 分钟
    private static final long PREFILTER_TTL_MS = 60 * 1000L;     // 60 秒

    // 统计计数器
    private final AtomicLong denseHits = new AtomicLong();
    private final AtomicLong denseMisses = new AtomicLong();
    private final AtomicLong sparseHits = new AtomicLong();
    private final AtomicLong sparseMisses = new AtomicLong();
    private final AtomicLong prefilterHits = new AtomicLong();
    private final AtomicLong prefilterMisses = new AtomicLong();

    // ── Dense Vector 缓存 ──────────────────────────────────────────────

    /**
     * 获取缓存的 dense vector。
     * @param queryText 查询文本
     * @return 缓存的向量，未命中或已过期返回 null
     */
    public List<Double> getDenseVector(String queryText) {
        CacheEntry<List<Double>> entry = denseVectorCache.get(queryText);
        if (entry != null && !entry.isExpired()) {
            denseHits.incrementAndGet();
            return entry.value;
        }
        if (entry != null) {
            denseVectorCache.remove(queryText, entry); // 清除过期条目
        }
        denseMisses.incrementAndGet();
        return null;
    }

    /**
     * 写入 dense vector 缓存。
     * @param queryText 查询文本
     * @param vector BGE-M3 dense 向量
     */
    public void putDenseVector(String queryText, List<Double> vector) {
        if (queryText == null || vector == null || vector.isEmpty()) return;
        evictIfNeeded(denseVectorCache);
        denseVectorCache.put(queryText, new CacheEntry<>(vector, VECTOR_TTL_MS));
    }

    // ── Sparse Vector 缓存 ──────────────────────────────────────────────

    /**
     * 获取缓存的 sparse vector。
     * @param queryText 查询文本
     * @return 缓存的稀疏向量 map，未命中或已过期返回 null
     */
    public Map<String, Double> getSparseVector(String queryText) {
        CacheEntry<Map<String, Double>> entry = sparseVectorCache.get(queryText);
        if (entry != null && !entry.isExpired()) {
            sparseHits.incrementAndGet();
            return entry.value;
        }
        if (entry != null) {
            sparseVectorCache.remove(queryText, entry);
        }
        sparseMisses.incrementAndGet();
        return null;
    }

    /**
     * 写入 sparse vector 缓存。
     * @param queryText 查询文本
     * @param sparseVec 稀疏向量 map
     */
    public void putSparseVector(String queryText, Map<String, Double> sparseVec) {
        if (queryText == null || sparseVec == null || sparseVec.isEmpty()) return;
        evictIfNeeded(sparseVectorCache);
        sparseVectorCache.put(queryText, new CacheEntry<>(sparseVec, VECTOR_TTL_MS));
    }

    // ── Prefilter 缓存 ──────────────────────────────────────────────

    /**
     * 获取缓存的 prefilter 候选结果。
     * @param cacheKey 缓存键（queryText + "|" + userId）
     * @return 缓存的候选 source 列表，未命中或已过期返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> getPrefilter(String cacheKey) {
        CacheEntry<List<?>> entry = prefilterCache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            prefilterHits.incrementAndGet();
            return (List<T>) entry.value;
        }
        if (entry != null) {
            prefilterCache.remove(cacheKey, entry);
        }
        prefilterMisses.incrementAndGet();
        return null;
    }

    /**
     * 写入 prefilter 缓存。
     * @param cacheKey 缓存键
     * @param candidates 候选 source 列表
     */
    public <T> void putPrefilter(String cacheKey, List<T> candidates) {
        if (cacheKey == null || candidates == null) return;
        evictIfNeeded(prefilterCache);
        prefilterCache.put(cacheKey, new CacheEntry<>(candidates, PREFILTER_TTL_MS));
    }

    // ── 工具方法 ──────────────────────────────────────────────────────

    /**
     * 当缓存超过容量上限时，随机驱逐 10% 条目（近似 LRU）。
     * 使用简单的随机驱逐而非严格的 LRU 实现，因为：
     * 1. ConcurrentHashMap 不支持有序遍历
     * 2. 搜索热点通常集中在最近查询，TTL 过期会自然清理冷数据
     */
    private <V> void evictIfNeeded(ConcurrentHashMap<String, CacheEntry<V>> cache) {
        if (cache.size() >= MAX_CACHE_SIZE) {
            int toRemove = MAX_CACHE_SIZE / 10;
            java.util.Iterator<Map.Entry<String, CacheEntry<V>>> it = cache.entrySet().iterator();
            while (it.hasNext() && toRemove > 0) {
                it.next();
                it.remove();
                toRemove--;
            }
        }
    }

    /**
     * 清除所有缓存（供测试和管理接口使用）。
     */
    public void clearAll() {
        denseVectorCache.clear();
        sparseVectorCache.clear();
        prefilterCache.clear();
    }

    /**
     * 获取缓存统计信息（供监控日志使用）。
     */
    public String getStats() {
        return String.format("[SearchCache] dense=%d(hits=%d/miss=%d) sparse=%d(hits=%d/miss=%d) prefilter=%d(hits=%d/miss=%d)",
                denseVectorCache.size(), denseHits.get(), denseMisses.get(),
                sparseVectorCache.size(), sparseHits.get(), sparseMisses.get(),
                prefilterCache.size(), prefilterHits.get(), prefilterMisses.get());
    }
}
