package com.boyang.search.service;

import com.boyang.search.entity.DocumentMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 负责将文档变更组装为 Kafka 消息发送
 */
@Service
public class KafkaProducerService {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    public static final String TOPIC_DOC_INGESTION = "doc-ingestion-topic";

    public void sendDocIngestionEvent(DocumentMetadata metadata) {
        Map<String, Object> event = new HashMap<>();
        event.put("action", "INGEST");
        event.put("metadata", metadata);
        event.put("timestamp", System.currentTimeMillis());
        
        kafkaTemplate.send(TOPIC_DOC_INGESTION, metadata.getObjectName(), event);
    }
}
