package com.loyalty.order.api;

import java.util.UUID;

/**
 * JSON body returned by POST /orders.
 * The eventId lets a caller (or a support engineer) trace this order through Kafka
 * and into the rewards-service ledger.
 */
public record OrderAcceptedResponse(String orderId, UUID eventId, String status) {
}
