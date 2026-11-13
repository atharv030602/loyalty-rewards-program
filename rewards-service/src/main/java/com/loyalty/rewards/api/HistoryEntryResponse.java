package com.loyalty.rewards.api;

import com.loyalty.rewards.ledger.LedgerEntry;
import java.time.Instant;

/**
 * One line of GET /members/{id}/history.
 *
 * We return this DTO instead of the JPA entity so the API does not change by accident
 * when a database column is added or renamed, and internal fields (id, eventId) stay private.
 */
public record HistoryEntryResponse(
        String type,
        long points,
        String orderId,
        String description,
        Instant createdAt) {

    public static HistoryEntryResponse from(LedgerEntry entry) {
        return new HistoryEntryResponse(
                entry.getEntryType().name(),
                entry.getPoints(),
                entry.getOrderId(),
                entry.getDescription(),
                entry.getCreatedAt());
    }
}
