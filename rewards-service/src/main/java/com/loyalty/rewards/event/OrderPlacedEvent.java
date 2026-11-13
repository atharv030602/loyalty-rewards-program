package com.loyalty.rewards.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * rewards-service's OWN copy of the event published by order-service.
 *
 * Why a copy and not a shared jar? A shared library would force both services to be
 * released together. The contract between them is the JSON, so each side keeps its own class.
 *
 * {@code ignoreUnknown = true}: if order-service adds a new field tomorrow, this consumer
 * keeps working instead of failing on JSON it does not know ("tolerant reader").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPlacedEvent(
        UUID eventId,
        String orderId,
        String memberId,
        BigDecimal amount,
        String category,
        Instant occurredAt) {
}
