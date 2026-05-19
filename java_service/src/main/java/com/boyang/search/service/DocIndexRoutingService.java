package com.boyang.search.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.boyang.search.entity.SysIndexRouting;
import com.boyang.search.mapper.SysIndexRoutingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态分片索引路由器 (服务实现层)
 *
 * 业务功能：替代原有的硬编码静态工具类，提供查表映射能力。
 * 为了保障 Java 入库的吞吐量，系统启动初期通过 @PostConstruct 会将 sys_index_routing 表
 * 中 is_active = 1 的配置全量提炼到内存的 ConcurrentHashMap 中，提供 O(1) 无延时映射响应。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocIndexRoutingService {

    private final SysIndexRoutingMapper sysIndexRoutingMapper;

    /** 核心内存映射字典，键：tag_name (如"法律法规"), 值：目标索引名 (如 "kb_document_law") */
    private final Map<String, String> routingCache = new ConcurrentHashMap<>();

    /** 内存级隔离出来的全局兜底索引名 */
    private String fallbackIndexCache = "kb_document_official"; // 后备初始默认值

    public static final String FALLBACK_KEY = "_FALLBACK_";

    /**
     * 系统启动时被框架自动调用，执行 DB 转缓存拉取动作。
     * 可供上游接口手动调用以进行“热更新”。
     */
    @PostConstruct
    public void refreshCache() {
        log.info("[DocIndexRoutingService] 正在从数据库加载分片路由策略...");
        try {
            LambdaQueryWrapper<SysIndexRouting> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SysIndexRouting::getIsActive, 1);
            List<SysIndexRouting> routingList = sysIndexRoutingMapper.selectList(wrapper);

            if (routingList == null || routingList.isEmpty()) {
                log.warn("[DocIndexRoutingService] 数据库未查询到有效的路由配置，将暂时只运用内定 fallback.");
                return;
            }

            Map<String, String> newCache = new ConcurrentHashMap<>();
            for (SysIndexRouting config : routingList) {
                // 如果命中约定的兜底健
                if (FALLBACK_KEY.equals(config.getTagName())) {
                    this.fallbackIndexCache = config.getTargetIndex();
                }
                // 加入到常规 Cache (同时包含了 fallback，无妨，方便跨端全量传输)
                newCache.put(config.getTagName(), config.getTargetIndex());
            }

            // 更新缓存空间
            routingCache.clear();
            routingCache.putAll(newCache);
            log.info("[DocIndexRoutingService] 成功载入 {} 组动态路由规则。 兜底索引 (Fallback): {}", 
                    newCache.size(), fallbackIndexCache);

        } catch (Exception e) {
            log.error("[DocIndexRoutingService] 初始化加载路由异常, 检查 DB 连接。 err: {}", e.getMessage());
        }
    }

    /**
     * 根据中文 tagName 获取当前应该走的分区 ES 索引名称。
     * 击穿(没查到、空、null)时，返回内部持有的 fallbackIndexCache。
     * 
     * @param tagName 上游系统传来的 tag 标签名称
     * @return 目标物理索引名
     */
    public String route(String tagName) {
        if (tagName == null || tagName.trim().isEmpty()) {
            return fallbackIndexCache;
        }
        String target = routingCache.get(tagName.trim());
        if (target == null) {
            // 避免频繁打印造成日志洪泛，使用 DEBUG 级别。如果是 DEBUG 以下将自动忽略输出。
            if(log.isDebugEnabled()) {
                log.debug("[DocIndexRoutingService] 遭遇未识别的分类标志 tag='{}', 将走向兜底区: {}", tagName, fallbackIndexCache);
            }
            return fallbackIndexCache;
        }
        return target;
    }

    /**
     * 获取当前的全局防破防兜底索引名 (用于其它服务手动提取)
     */
    public String getFallbackIndex() {
        return this.fallbackIndexCache;
    }

    /**
     * 将本服务全量的热缓存导出（供 Python/AI 等异构语言通过 API 爬取同步使用）
     */
    public Map<String, String> exportActiveRoutings() {
        return this.routingCache;
    }

    /**
     * 注册新文档类型路由规则（一站式原子操作）。
     *
     * 业务功能：
     *   将新的 tagName → indexName 映射同时写入数据库（sys_index_routing）和内存缓存，
     *   并异步通知 Python AI 服务在 ES 中预创建对应索引（利用 kb_document_* Template 继承标准 Mapping）。
     *   注册完成后对路由服务立即生效，无需重启任何服务组件。
     *
     * 关键流程：
     *   1. 幂等检查：tagName 已存在且 is_active=1 时，仅更新 description、target_index 字段
     *   2. 不存在则插入新行（is_active=1）
     *   3. 更新内存 routingCache（ConcurrentHashMap 线程安全）
     *   4. 异步调用 notifyPythonToEnsureIndex()（失败不影响注册结果，task_worker 有兜底）
     *
     * @param tagName     文档分类标签名（中文，如"合同文书"）
     * @param indexName   目标 ES 索引名（如"kb_document_contract"，必须以 kb_document_ 开头）
     * @param description 人类可读的描述（前端展示用）
     * @param operator    操作人 ID（审计用）
     */
    public void register(String tagName, String indexName, String description, String operator) {
        if (tagName == null || tagName.trim().isEmpty() || indexName == null || indexName.trim().isEmpty()) {
            throw new IllegalArgumentException("tagName 和 indexName 不能为空");
        }
        tagName   = tagName.trim();
        indexName = indexName.trim();

        // 1. 幂等写库：同名 tag 已存在则更新，不存在则插入
        LambdaQueryWrapper<SysIndexRouting> existWrapper = new LambdaQueryWrapper<>();
        existWrapper.eq(SysIndexRouting::getTagName, tagName);
        SysIndexRouting existing = sysIndexRoutingMapper.selectOne(existWrapper);

        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            // 已存在：更新索引名、描述、启用状态、更新人
            existing.setTargetIndex(indexName);
            existing.setDescription(description);
            existing.setIsActive(1);
            existing.setUpdateBy(operator);
            existing.setUpdateTime(now);
            sysIndexRoutingMapper.updateById(existing);
            log.info("[DocIndexRoutingService.register] 更新路由规则: tag='{}' → index='{}' (by={})",
                    tagName, indexName, operator);
        } else {
            // 不存在：新插入一行
            SysIndexRouting newRouting = new SysIndexRouting();
            newRouting.setTagName(tagName);
            newRouting.setTargetIndex(indexName);
            newRouting.setDescription(description);
            newRouting.setIsActive(1);
            newRouting.setCreateBy(operator);
            newRouting.setCreateTime(now);
            newRouting.setUpdateBy(operator);
            newRouting.setUpdateTime(now);
            sysIndexRoutingMapper.insert(newRouting);
            log.info("[DocIndexRoutingService.register] 新增路由规则: tag='{}' → index='{}' (by={})",
                    tagName, indexName, operator);
        }

        // 2. 即时热更新内存缓存（写完 DB 立刻生效，无需等待下次 refreshCache）
        routingCache.put(tagName, indexName);
        log.info("[DocIndexRoutingService.register] 内存路由缓存已热更新，当前共 {} 条规则", routingCache.size());

        // 3. 异步通知 Python AI 服务预创建 ES 索引（失败不影响注册结果，task_worker 写入时有前置兜底）
        final String finalIndexName = indexName;
        new Thread(() -> notifyPythonToEnsureIndex(finalIndexName), "routing-ensure-" + indexName).start();
    }

    /**
     * 软停用文档类型路由规则。
     *
     * 业务功能：将指定 tagName 的路由规则标记为 is_active=0（软删除），同时从内存缓存中移除。
     * 停用后该 tag 的新文档将路由至 fallback 索引（kb_document_official）。
     * 历史数据不受影响（不删除 ES 中已有的文档）。
     *
     * @param tagName  要停用的文档分类标签名
     * @param operator 操作人 ID（审计用）
     */
    public void deactivate(String tagName, String operator) {
        if (tagName == null || tagName.trim().isEmpty()) {
            throw new IllegalArgumentException("tagName 不能为空");
        }
        tagName = tagName.trim();

        LambdaQueryWrapper<SysIndexRouting> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysIndexRouting::getTagName, tagName);
        SysIndexRouting existing = sysIndexRoutingMapper.selectOne(wrapper);

        if (existing == null) {
            throw new IllegalArgumentException("路由规则不存在: " + tagName);
        }

        existing.setIsActive(0);
        existing.setUpdateBy(operator);
        existing.setUpdateTime(LocalDateTime.now());
        sysIndexRoutingMapper.updateById(existing);

        // 从内存缓存中移除（新上传的该 tag 文档将走 fallback）
        routingCache.remove(tagName);
        log.info("[DocIndexRoutingService.deactivate] 路由规则已停用: tag='{}' (by={})", tagName, operator);
    }

    /**
     * 异步通知 Python AI 服务预创建 ES 索引（非阻塞，失败仅打印警告）。
     *
     * 业务功能：调用 Python /internal/index/ensure 接口，让 Python 侧基于 Index Template
     *   立即创建目标索引，保证后续文档写入时索引已存在（无需等待 task_worker 首次写入触发兜底）。
     *
     * 设计原理：
     *   此为优化手段（提前创建），非强依赖。即使失败，task_worker 在写入前有 ensure_index_for() 兜底，
     *   因此本方法的失败只是损失索引提前创建的时机，不影响最终正确性。
     *
     * @param indexName 目标 ES 索引名
     */
    private void notifyPythonToEnsureIndex(String indexName) {
        String aiHost = System.getenv("AI_SERVICE_HOST");
        if (aiHost == null || aiHost.trim().isEmpty()) {
            aiHost = "http://localhost:8001";
        }
        String ensureUrl = aiHost.trim().replaceAll("/+$", "") + "/internal/index/ensure";
        try {
            String body = "{\"indexName\":\"" + indexName + "\"}";
            byte[] bodyBytes = body.getBytes("UTF-8");

            URL url = new URL(ensureUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            conn.setRequestProperty("X-Internal-Token",
                    System.getenv("KB_INTERNAL_TOKEN") != null
                    ? System.getenv("KB_INTERNAL_TOKEN")
                    : "kb-dev-token-change-me-in-prod");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.getOutputStream().write(bodyBytes);

            int code = conn.getResponseCode();
            if (code == 200) {
                log.info("[DocIndexRoutingService] Python 已确认创建 ES 索引: {}", indexName);
            } else {
                log.warn("[DocIndexRoutingService] Python 索引预创建响应异常 HTTP {}: {}", code, indexName);
            }
            conn.disconnect();
        } catch (Exception e) {
            // 非阻塞：失败只打警告，task_worker 写入时有前置兜底
            log.warn("[DocIndexRoutingService] 通知 Python 预创建索引失败（可接受）: {} → {}", indexName, e.getMessage());
        }
    }
}

