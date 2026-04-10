package com.boyang.search.consumer;

import com.boyang.search.model.DocIngestRequest;
import com.boyang.search.service.DocIngestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka 文档通知消费者（KafkaDocConsumer）。
 * 业务功能：监听外部系统推送到 Kafka Topic 的文档通知消息，
 *           解析后委托 DocIngestService 执行文件拉取和向量化入库。
 * 消息格式（JSON 字符串，对应 DocIngestRequest 字段）：
 * <pre>
 * {
 *   "ingestType": "SFTP",
 *   "credentialId": "fileserver-prod",
 *   "filePath": "/data/docs/2024/report.pdf",
 *   "visibility": "INTERNAL",
 *   "deptCode": "",
 *   "owner": "张三",
 *   "sourceSystem": "OA"
 * }
 * </pre>
 * ACK 策略：
 *   - 入库成功 → 立即手动 ACK，提交 offset
 *   - 入库失败 → 不 ACK，重启后重新消费（配合幂等哈希去重避免重复入库）
 *   - 消息格式错误 → 记录 WARN 并 ACK（跳过无效消息，防止 Consumer 卡死）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaDocConsumer {

    private final DocIngestService docIngestService;
    private final ObjectMapper     objectMapper;

    @Value("${kafka.topic.doc-notify:kb.doc.notify}")
    private String docNotifyTopic;

    /**
     * 监听文档通知 Topic，处理外部系统推送的新文档。
     *
     * @param record 消费到的 Kafka 消息（key=可选来源标识, value=JSON 格式 DocIngestRequest）
     * @param ack    手动 ACK 句柄，处理成功后调用
     */
    // @KafkaListener(
    //     topics      = "${kafka.topic.doc-notify:kb.doc.notify}",
    //     groupId     = "${spring.kafka.consumer.group-id:kb-doc-ingest}",
    //     containerFactory = "kafkaListenerContainerFactory"
    // )
    // public void onDocNotify(ConsumerRecord<String, String> record, Acknowledgment ack) {
    //     String rawMsg = record.value();
    //     String msgKey = record.key();
    //     long   offset = record.offset();
    //     int    partition = record.partition();

    //     log.info("[KafkaConsumer] 收到文档通知 topic={} partition={} offset={} key={}",
    //         docNotifyTopic, partition, offset, msgKey);

    //     // 1. 解析消息
    //     DocIngestRequest req;
    //     try {
    //         req = objectMapper.readValue(rawMsg, DocIngestRequest.class);
    //     } catch (Exception e) {
    //         // 消息格式错误：记录并跳过（ACK 掉防止反复消费垃圾消息）
    //         log.warn("[KafkaConsumer] 消息格式解析失败，已跳过 offset={} err={} raw={}",
    //             offset, e.getMessage(), rawMsg.length() > 200 ? rawMsg.substring(0, 200) : rawMsg);
    //         ack.acknowledge();
    //         return;
    //     }

    //     // 2. 执行入库
    //     try {
    //         String batchId = docIngestService.ingest(req);
    //         // 入库成功：提交 offset
    //         ack.acknowledge();
    //         log.info("[KafkaConsumer] 入库成功 batchId={} ingestType={} sourceSystem={} offset={}",
    //             batchId, req.getIngestType(), req.getSourceSystem(), offset);
    //     } catch (IllegalArgumentException e) {
    //         // 参数错误：这类消息重消费也会失败，直接 ACK 跳过并告警
    //         log.warn("[KafkaConsumer] 参数错误，已跳过 offset={} err={}", offset, e.getMessage());
    //         ack.acknowledge();
    //     } catch (SecurityException e) {
    //         // 安全拦截（SSRF 等）：直接跳过，避免恶意消息卡死 Consumer
    //         log.warn("[KafkaConsumer] 安全拦截，已跳过 offset={} err={}", offset, e.getMessage());
    //         ack.acknowledge();
    //     } catch (Exception e) {
    //         // 其他异常（网络超时/SFTP 不可达）：不 ACK，让 Consumer 重启后重消费
    //         log.error("[KafkaConsumer] 入库失败，不提交 offset，将重试 offset={} err={}",
    //             offset, e.getMessage(), e);
    //         // 注意：不调用 ack.acknowledge()，offset 不提交
    //     }
    // }
}
