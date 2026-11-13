package com.loyalty.rewards.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Database access for the ledger. Spring Data writes the implementation at startup:
 * save(), findById()... come from JpaRepository, and "derived" methods such as
 * existsByEventId are turned into SQL from their name.
 */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    boolean existsByEventId(UUID eventId);

    boolean existsByMemberId(String memberId);

    /** Newest first; id breaks the tie when two rows share the same timestamp. */
    List<LedgerEntry> findByMemberIdOrderByCreatedAtDescIdDesc(String memberId);

    /**
     * Balance = sum of all the member's rows (EARN rows are positive, REDEEM rows negative).
     * The database does the adding; we never load every row into memory.
     * COALESCE turns "no rows" (SUM = NULL) into 0.
     */
    @Query("select coalesce(sum(e.points), 0) from LedgerEntry e where e.memberId = :memberId")
    long sumPointsByMemberId(@Param("memberId") String memberId);
}
