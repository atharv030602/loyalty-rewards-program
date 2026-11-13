package com.loyalty.rewards.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.loyalty.rewards.IntegrationTestBase;
import com.loyalty.rewards.ledger.LedgerEntry;
import com.loyalty.rewards.ledger.LedgerEntryRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Integration tests for the Kafka consumer: real broker, real database.
 *
 * Each test writes the same JSON that order-service produces straight into "orders.placed"
 * and then looks at the results - the ledger table or the dead-letter topic.
 * Consuming is asynchronous, so we never sleep for a fixed time; Awaitility re-checks
 * the assertion until it passes or the timeout is reached.
 *
 * Every test uses its own random member id, so tests cannot disturb each other.
 */
class OrderPlacedConsumerIntegrationTest extends IntegrationTestBase {

    private static final String TOPIC = "orders.placed";
    private static final String DLT = "orders.placed.DLT";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private LedgerEntryRepository ledger;

    @Autowired
    private KafkaContainer kafka;

    @Test
    void orderEventAwardsPoints() {
        String member = newMember();

        send(member, orderJson(UUID.randomUUID(), "ORD-1", member, "2500", "TRAVEL"));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(ledger.sumPointsByMemberId(member)).isEqualTo(50));
    }

    @Test
    void twoDifferentOrdersForOneMemberBothAwardPoints() {
        // The exact case from manual testing: ORD-1 (2500, TRAVEL) then ORD-2 (1000, DINING).
        String member = newMember();

        send(member, orderJson(UUID.randomUUID(), "ORD-1", member, "2500", "TRAVEL"));
        send(member, orderJson(UUID.randomUUID(), "ORD-2", member, "1000", "DINING"));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(ledger.sumPointsByMemberId(member)).isEqualTo(80));
        assertThat(ledger.findByMemberIdOrderByCreatedAtDescIdDesc(member))
                .extracting(LedgerEntry::getOrderId, LedgerEntry::getPoints)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ORD-2", 30L),
                        org.assertj.core.groups.Tuple.tuple("ORD-1", 50L));
    }

    @Test
    void sameEventDeliveredTwiceAwardsPointsOnce() {
        String member = newMember();
        UUID eventId = UUID.randomUUID();
        String duplicate = orderJson(eventId, "ORD-1", member, "2500", "TRAVEL");

        send(member, duplicate);
        send(member, duplicate);
        // A third, different event for the same member. Same key -> same partition -> it is
        // processed AFTER both copies above, so once it shows up we know the duplicate was handled.
        send(member, orderJson(UUID.randomUUID(), "ORD-MARKER", member, "100", "OTHER"));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(ledger.findByMemberIdOrderByCreatedAtDescIdDesc(member))
                        .extracting(LedgerEntry::getOrderId)
                        .contains("ORD-MARKER"));

        assertThat(ledger.findByMemberIdOrderByCreatedAtDescIdDesc(member))
                .extracting(LedgerEntry::getOrderId)
                .containsExactlyInAnyOrder("ORD-1", "ORD-MARKER");
        assertThat(ledger.sumPointsByMemberId(member)).isEqualTo(51);   // 50 + 1, not 101
    }

    @Test
    void unknownFieldsInTheEventAreIgnored() {
        // order-service may add fields later; this consumer must keep working.
        String member = newMember();
        String json = """
                {"eventId":"%s","orderId":"ORD-NEW","memberId":"%s","amount":300,
                 "category":"GROCERY","occurredAt":"2026-01-01T00:00:00Z","couponCode":"FESTIVE10"}
                """.formatted(UUID.randomUUID(), member);

        send(member, json);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(ledger.sumPointsByMemberId(member)).isEqualTo(3));
    }

    @Test
    void unreadableMessageGoesToTheDeadLetterTopicAndTheConsumerKeepsWorking() {
        String member = newMember();

        send(member, "this is not json");
        send(member, orderJson(UUID.randomUUID(), "ORD-AFTER", member, "500", "OTHER"));

        // The bad message is parked in the DLT, untouched...
        ConsumerRecord<String, String> dead = awaitDeadLetter(member);
        assertThat(dead.value()).isEqualTo("this is not json");
        // ...and the next message on the same partition is still processed.
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(ledger.sumPointsByMemberId(member)).isEqualTo(5));
    }

    @Test
    void eventWithAMissingFieldGoesToTheDeadLetterTopicWithoutRetries() {
        String member = newMember();
        String noAmount = """
                {"eventId":"%s","orderId":"ORD-BAD","memberId":"%s","category":"TRAVEL"}
                """.formatted(UUID.randomUUID(), member);

        send(member, noAmount);

        ConsumerRecord<String, String> dead = awaitDeadLetter(member);
        assertThat(dead.value()).contains("ORD-BAD");
        assertThat(ledger.existsByMemberId(member)).isFalse();
    }

    @Test
    void temporaryFailureIsRetriedUntilItSucceeds() {
        String member = newMember();
        String category = "FLAKY-" + UUID.randomUUID();
        // Fail twice (like a database that is briefly down), then behave normally.
        doThrow(new IllegalStateException("temporary failure 1"))
                .doThrow(new IllegalStateException("temporary failure 2"))
                .doCallRealMethod()
                .when(pointsCalculator).calculate(any(), eq(category));

        send(member, orderJson(UUID.randomUUID(), "ORD-RETRY", member, "400", category));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(ledger.sumPointsByMemberId(member)).isEqualTo(4));
        verify(pointsCalculator, times(3)).calculate(any(), eq(category));
        assertThat(ledger.findByMemberIdOrderByCreatedAtDescIdDesc(member)).hasSize(1);
    }

    @Test
    void permanentFailureIsRetriedThreeTimesThenSentToTheDeadLetterTopic() {
        String member = newMember();
        String category = "BROKEN-" + UUID.randomUUID();
        doThrow(new IllegalStateException("always fails"))
                .when(pointsCalculator).calculate(any(), eq(category));

        send(member, orderJson(UUID.randomUUID(), "ORD-DEAD", member, "400", category));

        ConsumerRecord<String, String> dead = awaitDeadLetter(member);
        assertThat(dead.value()).contains("ORD-DEAD");
        // The DLT record carries headers that explain why it failed.
        assertThat(dead.headers().lastHeader("kafka_dlt-exception-message")).isNotNull();
        assertThat(dead.headers().lastHeader("kafka_dlt-original-topic").value())
                .isEqualTo(TOPIC.getBytes(StandardCharsets.UTF_8));
        // 1 first attempt + 3 retries.
        verify(pointsCalculator, times(4)).calculate(any(), eq(category));
        assertThat(ledger.existsByMemberId(member)).isFalse();
    }

    // ---------- helpers ----------

    private static String newMember() {
        return "M-" + UUID.randomUUID();
    }

    private static String orderJson(UUID eventId, String orderId, String member, String amount, String category) {
        return """
                {"eventId":"%s","orderId":"%s","memberId":"%s","amount":%s,"category":"%s","occurredAt":"2026-11-13T10:15:30Z"}
                """.formatted(eventId, orderId, member, amount, category).trim();
    }

    /** Sends raw bytes, so the topic contains exactly the text we wrote. */
    private void send(String key, String value) {
        kafkaTemplate.send(TOPIC, key, value.getBytes(StandardCharsets.UTF_8)).join();
    }

    private ConsumerRecord<String, String> awaitDeadLetter(String key) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(DLT));
            await().atMost(TIMEOUT).untilAsserted(() -> {
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
}
