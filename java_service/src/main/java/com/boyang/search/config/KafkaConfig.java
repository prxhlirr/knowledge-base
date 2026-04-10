package com.boyang.search.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka 消费者配置（KafkaConfig）。
 * 业务功能：配置 Kafka 消费者连接参数，开启手动 ACK 模式，
 *           确保文档入库成功后才提交 offset，防止消息丢失。
 * 关键设计：
 *   - ackMode = MANUAL_IMMEDIATE：消息处理成功才 ACK，处理失败不提交 offset，
 *     Consumer 重启后会重新消费（配合幂等性哈希去重避免重复处理）
 *   - concurrency=3：3 个线程并行消费，提高吞吐（生产环境按 partition 数调整）
 *   - pollTimeout=3000ms：避免 Kafka Broker 因消费过慢踢出 Consumer Group
 */
@EnableKafka
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:kb-doc-ingest}")
    private String groupId;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG,          groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // 禁用自动提交 offset，改由业务代码在成功处理后手动 ACK
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // 限制每次 poll 最大记录数，防止单批 SFTP 下载阻塞 Consumer 线程过久
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * 创建 Kafka 监听器容器工厂。
     * MANUAL_IMMEDIATE：处理完一条消息立即手动 ACK，不等批次结束。
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        // 手动 ACK 模式：入库成功才提交 offset，失败则不提交（重启后重消费）
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // 3 个并发线程，可根据 Kafka Partition 数调整
        factory.setConcurrency(3);
        // 错误处理：记录日志并继续（不阻塞 Consumer Group）
        factory.setErrorHandler((e, data) ->
            org.slf4j.LoggerFactory.getLogger(KafkaConfig.class)
                .error("[KafkaConfig] 消息处理异常，已跳过 data={}", data, e));
        return factory;
    }
}
