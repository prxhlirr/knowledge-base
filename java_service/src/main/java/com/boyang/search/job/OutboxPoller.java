package com.boyang.search.job;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.UpdateByQueryRequest;
import com.boyang.search.entity.KbDocOutbox;
import com.boyang.search.mapper.KbDocOutboxMapper;
import com.boyang.search.service.IndexAliasResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;


/**
 * Transactional Outbox 轮询激活器（OutboxPoller）。
 *
 * 业务功能：定时扫描 kb_doc_outbox 表中状态为 READY 的记录，
 *           执行 ES update_by_query 将对应文档版本的 is_latest 置为 true，
 *           完成"以不可见方式写入，确认后激活可见"的最终步骤。
 *
 * 执行流程（每 10 秒执行一次）：
 *   1. 查询 status=READY 的 outbox 记录（每批最多 50 条）
 *   2. 对每条记录执行单次 ES update_by_query：
 *      - 同 source_name 下 doc_version=N 的分片设为 true，其余版本设为 false
 *   3. 更新 outbox.status = DONE（或 FAILED，后者触发 retry_count 递增）
 *
 * 并发安全：
 *   - 使用 CAS 风格 updateStatusById 防止重复激活
 *   - 单实例部署时无需分布式锁；集群部署时建议引入分布式锁或数据库行锁
 *
 * 最大重试次数：MAX_RETRY（默认 3 次），超过后置 FAILED，需运维人工干预。
 *
 * 为何使用 co.elastic.clients.ElasticsearchClient 而非 RestHighLevelClient：
 *   项目依赖 elasticsearch-java:8.6.2，该版本的 HLRC（RestHighLevelClient）
 *   已从 elasticsearch-rest-high-level-client 包中移除，org.elasticsearch.* 相关包
 *   均不在 classpath 内，唯一可用的是 co.elastic.clients 新 Java Client API。
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class OutboxPoller {

    private final KbDocOutboxMapper outboxMapper;
    /** 使用项目统一注入的 ES 8.x Java Client（co.elastic.clients） */
    private final ElasticsearchClient esClient;
    private final com.boyang.search.service.IndexAliasResolver indexAliasResolver;
    private final StringRedisTemplate stringRedisTemplate;


    /** 是否启用 Outbox Poller（开发环境可关闭） */
    @Value("${outbox.poller.enabled:true}")
    private boolean pollerEnabled;

    /** 单次轮询最大处理条数（防止单批次过大阻塞正常流量） */
    private static final int POLL_BATCH_SIZE = 50;

    /** 最大重试次数（超过后置 FAILED） */
    private static final int MAX_RETRY = 3;

    /**
     * 定时扫描并激活 READY 状态的 Outbox 记录（默认每 10 秒执行一次）。
     * 可通过 outbox.poller.cron 配置 Cron 表达式调整频率。
     * 推荐生产配置：
     *   正常负载：每10秒（"0/10 * * * * ?"）
     *   高吞吐：  每5秒 ("0/5 * * * * ?")
     */
    @Scheduled(cron = "${outbox.poller.cron:0/10 * * * * ?}")
    public void poll() {
        if (!pollerEnabled) return;

        List<KbDocOutbox> readyList = outboxMapper.findByStatusWithLimit("READY", POLL_BATCH_SIZE);
        if (readyList.isEmpty()) return;

        log.info("[OutboxPoller] 开始扫描处理，拉取到 {} 条 READY 记录", readyList.size());

        for (KbDocOutbox outbox : readyList) {
            String sourceName = outbox.getSourceName();
            if (sourceName == null || sourceName.isEmpty()) {
                continue;
            }

            // 1. 获取以 sourceName 维度的分布式锁，防止并发乱序与多实例重复处理同一任务
            String lockKey = "lock:es:activate:" + sourceName;
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "locked", Duration.ofSeconds(30));
            if (Boolean.FALSE.equals(acquired)) {
                log.info("[OutboxPoller] 获取 ES 激活锁失败，可能其他实例正在处理该文件，跳过 id={} sourceName={}", 
                        outbox.getId(), sourceName);
                continue;
            }

            try {
                // 2. 双重检测 (DCL)：在锁保护下重新查询数据库最新状态，防范状态在捞出后已被其他线程更新
                KbDocOutbox current = outboxMapper.selectById(outbox.getId());
                if (current == null || !"READY".equals(current.getStatus())) {
                    log.info("[OutboxPoller] 双重检测未通过，记录已被其他实例处理，跳过 id={}", outbox.getId());
                    continue;
                }

                // 3. 执行 ES 版本切换
                activateInEs(current);
                
                // 4. 更新 outbox 状态为 DONE
                outboxMapper.updateStatusById(current.getId(), "DONE", null);
                log.info("[OutboxPoller] 成功激活 id={} taskId={} sourceName={} v{}",
                    current.getId(), current.getTaskId(), current.getSourceName(), current.getDocVersion());
            } catch (Exception e) {
                int retryCount = outbox.getRetryCount() != null ? outbox.getRetryCount() : 0;
                if (retryCount >= MAX_RETRY) {
                    outboxMapper.updateStatusById(outbox.getId(), "FAILED",
                        "激活失败枯竭: " + e.getMessage());
                    log.error("[OutboxPoller] 激活失败达上限 FAILED id={} taskId={} err={}",
                        outbox.getId(), outbox.getTaskId(), e.getMessage());
                } else {
                    outboxMapper.incrementRetryCount(outbox.getId(), e.getMessage());
                    log.warn("[OutboxPoller] 激活失败，递增重试次数 retry_count={} id={} taskId={} err={}",
                        retryCount + 1, outbox.getId(), outbox.getTaskId(), e.getMessage());
                }
            } finally {
                // 5. 必须在 finally 块中释放锁
                stringRedisTemplate.delete(lockKey);
            }
        }
    }

    private void activateInEs(KbDocOutbox outbox) throws Exception {
        String sourceName  = outbox.getSourceName();
        int    docVersion  = outbox.getDocVersion() != null ? outbox.getDocVersion() : 1;
        // 激活必须命中 chunk 实际所在的物理索引（kb_document_*_v2，未来 _v3）。
        // outbox 存的是逻辑名（如 kb_document_law），normalizeWriteTarget 会把它原样返回，
        // 但同名存在一个 0 条的空壳遗留具体索引（非别名，_alias/kb_document_law → 404），
        // 会让 update_by_query 打在空壳上 0 命中、却因 conflicts=Proceed 吞成"成功激活"，
        // chunk 永远 is_latest=false。改打读别名 kb_document（覆盖所有版本化 chunk 索引、
        // 不含空壳遗留索引）+ metadata.source 过滤，彻底绕开该解析陷阱。
        String targetIndex = IndexAliasResolver.DOCUMENT_READ_ALIAS;

        // 单次脚本切换：同一 source_name 下，新版本设为 true，其余版本设为 false。
        UpdateByQueryRequest switchLatest = new UpdateByQueryRequest.Builder()
            .index(targetIndex)
            .query(q -> q.term(t -> t.field("metadata.source").value(sourceName)))
            .script(s -> s.inline(i -> i
                .lang("painless")
                .source(
                    "if (ctx._source.metadata.doc_version != null && " +
                    "ctx._source.metadata.doc_version.toString().equals(params.ver.toString())) { " +
                    "ctx._source.metadata.is_latest = true; " +
                    "} else { " +
                    "ctx._source.metadata.is_latest = false; " +
                    "}"
                )
                .params("ver", co.elastic.clients.json.JsonData.of(docVersion))
            ))
            .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
            .refresh(true)
            .build();
        esClient.updateByQuery(switchLatest);

        log.debug("[OutboxPoller] ES 版本切换完成 sourceName={} v={} index={}",
            sourceName, docVersion, targetIndex);
    }
}
