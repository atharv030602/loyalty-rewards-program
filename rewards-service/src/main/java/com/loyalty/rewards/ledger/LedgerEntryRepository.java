package com.loyalty.rewards.ledger;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Database access for the ledger. Spring Data writes the implementation at startup:
 * save(), findById()... come from JpaRepository, and the method below is turned into
 * SQL from its name ("exists ... where event_id = ?").
 */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    boolean existsByEventId(UUID eventId);
}
