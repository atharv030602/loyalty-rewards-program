package com.loyalty.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test: the real application + a real Kafka broker (Testcontainers).
 *
 * It proves what a mock cannot: the JSON that actually lands in the topic, the message key,
 * and that the topic is created with 3 partitions. The test reads the topic with a plain
 * Kafka consumer, exactly as another service would. (Docker Desktop must be running.)
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderPublishingIntegrationTest {

    // @ServiceConnection points spring.kafka.bootstrap-servers at this container.
    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void postedOrderArrivesInKafkaAsJsonKeyedByMember() throws Exception {
        String memberId = "M-" + UUID.randomUUID();

        ResponseEntity<Map> response = rest.postForEntity("/orders",
                Map.of("orderId", "ORD-2", "memberId", memberId, "amount", 1000, "category", "DINING"),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String eventId = (String) response.getBody().get("eventId");

        ConsumerRecord<String, String> record = readOneRecordWithKey(memberId);

        JsonNode json = objectMapper.readTree(record.value());
        assertThat(json.get("eventId").asText()).isEqualTo(eventId);
        assertThat(json.get("orderId").asText()).isEqualTo("ORD-2");
        assertThat(json.get("memberId").asText()).isEqualTo(memberId);
        assertThat(json.get("amount").decimalValue()).isEqualByComparingTo("1000");
        assertThat(json.get("category").asText()).isEqualTo("DINING");
        // ISO-8601 text, not a number: Instant.parse would throw otherwise.
        assertThat(Instant.parse(json.get("occurredAt").asText())).isBeforeOrEqualTo(Instant.now());
        // No Java class name leaks to the consumer.
        assertThat(record.headers().lastHeader("__TypeId__")).isNull();
    }

    @Test
    void topicIsCreatedWithThreePartitions() {
        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            assertThat(consumer.partitionsFor("orders.placed")).hasSize(3);
        }
    }

    @Test
    void invalidOrderPublishesNothing() {
        String memberId = "M-" + UUID.randomUUID();

        ResponseEntity<Map> response = rest.postForEntity("/orders",
                Map.of("orderId", "ORD-BAD", "memberId", memberId, "amount", -1, "category", "DINING"),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(readAllRecords(Duration.ofSeconds(3)))
                .noneMatch(record -> memberId.equals(record.key()));
    }

    private ConsumerRecord<String, String> readOneRecordWithKey(String key) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of("orders.placed"));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                });
                assertThat(found).hasSize(1);
            });
        }
        return found.get(0);
    }

    private List<ConsumerRecord<String, String>> readAllRecords(Duration howLong) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of("orders.placed"));
            long end = System.currentTimeMillis() + howLong.toMillis();
            while (System.currentTimeMillis() < end) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }

    private KafkaConsumer<String, String> newConsumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                // A fresh group every time, reading from the start of the topic.
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }
}
