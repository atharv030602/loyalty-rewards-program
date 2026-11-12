package com.loyalty.order.messaging;

import com.loyalty.order.event.OrderPlacedEvent;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * The only class in order-service that talks to Kafka.
 * The controller depends on this class, not on KafkaTemplate, so the web layer
 * stays unaware of topics, keys and serializers (and is easy to mock in tests).
 */
@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    /** A little more than delivery.timeout.ms (10s), so Kafka's own timeout fires first. */
    private static final long SEND_TIMEOUT_SECONDS = 12;

    private final KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate;
    private final String topic;

    public OrderEventPublisher(KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate,
                               @Value("${app.kafka.topics.orders-placed}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * Sends the event and WAITS for the broker's acknowledgement.
     *
     * {@code send()} is asynchronous: it returns a future immediately. Calling {@code get()}
     * blocks until the broker confirms (acks=all). We accept that small delay so that
     * "202 Accepted" really means "your order event is safely stored in Kafka".
     *
     * @throws EventPublishException if the broker did not confirm in time
     */
    public void publish(OrderPlacedEvent event) {
        try {
            // key = memberId -> same member always goes to the same partition -> ordering per member
            SendResult<String, OrderPlacedEvent> result = kafkaTemplate
                    .send(topic, event.memberId(), event)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            log.info("Published OrderPlaced eventId={} orderId={} to {}-{} at offset {}",
                    event.eventId(), event.orderId(), topic,
                    result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
        } catch (InterruptedException e) {
            // Restore the interrupt flag so the thread pool can shut down cleanly.
            Thread.currentThread().interrupt();
            throw new EventPublishException("Interrupted while publishing order " + event.orderId(), e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("Could not publish OrderPlaced for orderId={}", event.orderId(), e);
            throw new EventPublishException("Could not publish order " + event.orderId(), e);
        }
    }
}
