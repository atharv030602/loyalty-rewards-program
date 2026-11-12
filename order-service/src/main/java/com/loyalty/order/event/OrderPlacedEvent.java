package com.loyalty.order.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The message we publish to Kafka when an order is accepted.
 *
 * An event is a fact about the past ("an order WAS placed"), so it is immutable -
 * a Java record fits perfectly: final fields, no setters.
 *
 * This JSON shape is the contract between order-service and rewards-service.
 * Changing a field name here is a breaking change for every consumer.
 *
 * @param eventId    unique id of this event; the consumer uses it to detect duplicates
 * @param orderId    business id of the order, supplied by the caller
 * @param memberId   loyalty member who placed the order (also the Kafka message key)
 * @param amount     order amount in INR
 * @param category   spend category, e.g. TRAVEL or DINING (sent as plain text)
 * @param occurredAt when order-service accepted the order (UTC)
 */
public record OrderPlacedEvent(
        UUID eventId,
        String orderId,
        String memberId,
        BigDecimal amount,
        String category,
        // Without this, Jackson writes an Instant as a number (1794508054.12). Force the
        // readable ISO-8601 text form, e.g. "2026-11-12T18:27:34.121Z" - any language can parse it.
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant occurredAt) {
}
