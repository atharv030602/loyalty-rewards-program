package com.loyalty.rewards.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the points ledger (table {@code points_ledger}).
 *
 * Rows are append-only: there are no setters, so a saved entry can never be changed.
 * A correction is a new row, exactly like a bank statement.
 */
@Entity
@Table(name = "points_ledger")
public class LedgerEntry {

    @Id
    // IDENTITY = let PostgreSQL's BIGSERIAL column generate the id on insert.
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "member_id", nullable = false)
    private String memberId;

    @Column(name = "order_id")
    private String orderId;

    // Store the enum as text ("EARN"), not as a number (0). Numbers break silently
    // if someone later reorders the enum constants.
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private EntryType entryType;

    @Column(nullable = false)
    private long points;

    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA needs a no-argument constructor to build objects from query results. */
    protected LedgerEntry() {
    }

    private LedgerEntry(UUID eventId, String memberId, String orderId,
                        EntryType entryType, long points, String description) {
        this.eventId = eventId;
        this.memberId = memberId;
        this.orderId = orderId;
        this.entryType = entryType;
        this.points = points;
        this.description = description;
        this.createdAt = Instant.now();
    }

    /** Factory method: the only way to create an "earned points" row. */
    public static LedgerEntry earn(UUID eventId, String memberId, String orderId,
                                   long points, String description) {
        return new LedgerEntry(eventId, memberId, orderId, EntryType.EARN, points, description);
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getMemberId() {
        return memberId;
    }

    public String getOrderId() {
        return orderId;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public long getPoints() {
        return points;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
