package com.boyang.search.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.boyang.search.entity.SysGovSynonym;
import com.boyang.search.mapper.SysGovSynonymMapper;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 政务同义词词典服务。
 *
 * 业务功能：
 *   1. 提供 CRUD 接口，支撑后台管理界面的同义词维护
 *   2. 维护内存缓存（synonymMap）供 SearchService 高频访问，避免每次查询都走数据库
 *   3. 提供 reloadCache() 接口，在词典更新后由 Controller 主动触发热重载
 *
 * 性能考量：
 *   - 同义词词典通常不超过数百条，全量加载至内存完全合理
 *   - ConcurrentHashMap 保证 SearchService 并发读取的线程安全
 *   - 写操作（新增/修改/删除）后强制 reloadCache()，确保下一次查询即刻生效
 */
@Service
public class SysGovSynonymService extends ServiceImpl<SysGovSynonymMapper, SysGovSynonym> {

    /**
     * 内存缓存：abbr → 展开词列表（含 abbr 本身）
     * 值中始终包含 abbr 本身，确保 BM25 扩展后不丢失原始词的匹配
     */
    private volatile Map<String, List<String>> synonymMap = new ConcurrentHashMap<>();

    /**
     * 服务启动时的初始化加载。
     * 若数据库不可用，捕获异常并打印警告，不阻断服务启动。
     */
    @PostConstruct
    public void initCache() {
        try {
            reloadCache();
            System.out.println("[SysGovSynonymService] 政务同义词缓存初始化完成，共加载 "
                + synonymMap.size() + " 条词条");
        } catch (Exception e) {
            System.err.println("[SysGovSynonymService] 同义词缓存初始化失败（忽略，不影响服务启动）: " + e.getMessage());
        }
    }

    /**
     * 热重载内存缓存。
     * 由 Controller 在词典变更后调用，确保下次 SearchService 查询时使用最新词典。
     */
    public void reloadCache() {
        List<SysGovSynonym> rows = list(new LambdaQueryWrapper<SysGovSynonym>()
            .eq(SysGovSynonym::getEnabled, 1));

        Map<String, List<String>> newMap = new ConcurrentHashMap<>();
        for (SysGovSynonym row : rows) {
            // 展开词列表：abbr 本身 + fullTerms 中每个词
            List<String> terms = new ArrayList<>();
            terms.add(row.getAbbr());
            if (row.getFullTerms() != null && !row.getFullTerms().trim().isEmpty()) {
                for (String t : row.getFullTerms().split(",")) {
                    String trimmed = t.trim();
                    if (!trimmed.isEmpty() && !terms.contains(trimmed)) {
                        terms.add(trimmed);
                    }
                }
            }
            newMap.put(row.getAbbr(), terms);

            // EQUIV 类型：双向展开，fullTerms 中每个词也作为触发键
            if ("EQUIV".equals(row.getSynonymType())) {
                for (String fullTerm : new ArrayList<>(terms)) {
                    if (!fullTerm.equals(row.getAbbr())) {
                        newMap.putIfAbsent(fullTerm, terms);
                    }
                }
            }
        }
        // 原子替换，避免 SearchService 读到中间状态
        this.synonymMap = Collections.unmodifiableMap(newMap);
        System.out.println("[SysGovSynonymService] 同义词缓存重载完成，共 " + newMap.size() + " 个触发键");
    }

    /**
     * 获取当前同义词缓存（只读），供 SearchService 高频调用。
     *
     * @return abbr → 展开词列表的只读映射
     */
    public Map<String, List<String>> getSynonymMap() {
        return synonymMap;
    }

    /**
     * 新增同义词条目。
     * 写入数据库后强制刷新缓存，确保即时生效。
     */
    public boolean addSynonym(SysGovSynonym synonym) {
        synonym.setEnabled(synonym.getEnabled() != null ? synonym.getEnabled() : 1);
        synonym.setSynonymType(synonym.getSynonymType() != null ? synonym.getSynonymType() : "ABBR");
        synonym.setCreatedAt(LocalDateTime.now());
        synonym.setUpdatedAt(LocalDateTime.now());
        boolean result = save(synonym);
        if (result) reloadCache();
        return result;
    }

    /**
     * 更新同义词条目。
     */
    public boolean updateSynonym(SysGovSynonym synonym) {
        synonym.setUpdatedAt(LocalDateTime.now());
        boolean result = updateById(synonym);
        if (result) reloadCache();
        return result;
    }

    /**
     * 删除同义词条目（物理删除，同义词无历史溯源需求）。
     */
    public boolean deleteSynonym(Long id) {
        boolean result = removeById(id);
        if (result) reloadCache();
        return result;
    }

    /**
     * 获取所有词条（含停用），供后台管理界面展示。
     */
    public List<SysGovSynonym> listAll() {
        return list(new LambdaQueryWrapper<SysGovSynonym>()
            .orderByAsc(SysGovSynonym::getSynonymType)
            .orderByAsc(SysGovSynonym::getAbbr));
    }
}
