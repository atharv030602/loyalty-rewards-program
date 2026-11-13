package com.loyalty.rewards.messaging;

import com.loyalty.rewards.event.OrderPlacedEvent;
import com.loyalty.rewards.ledger.RewardsService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Entry point for messages from Kafka - the consumer-side twin of a REST controller.
 * It only checks the message and delegates; the business logic lives in RewardsService.
 *
 * How offsets work here: Spring commits the offset only AFTER this method returns normally.
 * If it throws, the offset is not committed and the error handler (KafkaConsumerConfig)
 * retries the message, then sends it to the dead-letter topic. That is "at-least-once".
 */
@Component
public class OrderPlacedListener {

    private final RewardsService rewardsService;

    public OrderPlacedListener(RewardsService rewardsService) {
        this.rewardsService = rewardsService;
    }

    @KafkaListener(topics = "${app.kafka.topics.orders-placed}")
    public void onOrderPlaced(OrderPlacedEvent event) {
        validate(event);
        rewardsService.awardPoints(event);
    }

    /**
     * Never trust a message just because it came from "our own" service.
     * IllegalArgumentException is marked non-retryable in KafkaConsumerConfig:
     * a message with a missing field will be just as broken on the 4th attempt.
     */
    private static void validate(OrderPlacedEvent event) {
        if (event.eventId() == null) {
            throw new IllegalArgumentException("OrderPlaced event has no eventId");
        }
        if (event.memberId() == null || event.memberId().isBlank()) {
            throw new IllegalArgumentException("OrderPlaced event " + event.eventId() + " has no memberId");
        }
        if (event.amount() == null) {
            throw new IllegalArgumentException("OrderPlaced event " + event.eventId() + " has no amount");
        }
    }
}
