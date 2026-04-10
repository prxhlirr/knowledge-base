package com.boyang.search.event;

import org.springframework.context.ApplicationEvent;

/**
 * 文档任务就绪事件（DocTaskReadyEvent）。
 *
 * 业务功能：在 DocIngestService.createAndDispatch() 完成 MySQL 事务性写入后，
 *           通过 Spring 事件机制安全地触发 Redis lpush，保证：
 *           "Redis 入队发生在 MySQL 事务 COMMIT 之后"。
 *
 * 解决的核心问题（Transactional Outbox Pattern 的关键细节）：
 *   - 若在 MySQL 事务内直接调用 Redis lpush，事务回滚后 Redis 已入队，
 *     Python Worker 消费到一个永远不存在于 MySQL 的任务 → 统计失真、日志混乱。
 *   - 通过 @TransactionalEventListener(phase=AFTER_COMMIT) 监听此事件，
 *     确保 Redis 入队严格发生在 MySQL COMMIT 成功之后。
 *
 * 事件发布方：DocIngestService（在 @Transactional 方法内发布）
 * 事件监听方：DocIngestService 的内部监听器（@TransactionalEventListener）
 */
public class DocTaskReadyEvent extends ApplicationEvent {

    /** Redis 队列 Key（HIGH 或 LOW 优先级队列名） */
    private final String queueKey;

    /** 序列化后的 JSON Payload（直接 lpush 到 Redis） */
    private final String jsonPayload;

    /** 任务 ID（用于日志追踪） */
    private final String taskId;

    /**
     * 构造文档任务就绪事件。
     *
     * @param source      事件来源（通常为发布者 this）
     * @param taskId      任务唯一标识
     * @param queueKey    目标 Redis 队列 Key
     * @param jsonPayload 完整 JSON 字符串 Payload
     */
    public DocTaskReadyEvent(Object source, String taskId, String queueKey, String jsonPayload) {
        super(source);
        this.taskId      = taskId;
        this.queueKey    = queueKey;
        this.jsonPayload = jsonPayload;
    }

    public String getQueueKey()    { return queueKey;    }
    public String getJsonPayload() { return jsonPayload; }
    public String getTaskId()      { return taskId;      }
}
