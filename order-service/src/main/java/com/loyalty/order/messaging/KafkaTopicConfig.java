package com.loyalty.order.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topic this service writes to.
 *
 * Spring Boot's KafkaAdmin sees every {@link NewTopic} bean at startup and creates the
 * topic if it does not exist yet. Declaring it in code (instead of relying on Kafka's
 * "auto-create on first message") means the partition count is a decision, not an accident.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic ordersPlacedTopic(@Value("${app.kafka.topics.orders-placed}") String topicName) {
        return TopicBuilder.name(topicName)
                // 3 partitions: up to 3 consumer instances can share the work later.
                // Messages with the same key (memberId) always land in the same partition,
                // so one member's orders are still processed in order.
                .partitions(3)
                // 1 copy only, because our local cluster has a single broker.
                .replicas(1)
                .build();
    }
}
