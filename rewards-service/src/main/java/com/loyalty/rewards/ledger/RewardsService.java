package com.loyalty.rewards.ledger;

import com.loyalty.rewards.event.OrderPlacedEvent;
import com.loyalty.rewards.points.PointsCalculator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic of the points ledger: earn, balance, history, redeem.
 *
 * EARNING must be idempotent. Kafka delivers "at least once": after a crash or a rebalance
 * the same message can arrive again, and processing it twice must leave the ledger exactly
 * as if it was processed once. Two layers of protection:
 * 1. A quick check: "have we stored this eventId already?" - handles the normal duplicate.
 * 2. The UNIQUE constraint on event_id - handles the race where two threads pass
 *    check 1 at the same moment. The database lets only one insert win.
 */
@Service
public class RewardsService {

    private static final Logger log = LoggerFactory.getLogger(RewardsService.class);

    private final LedgerEntryRepository ledger;
    private final PointsCalculator pointsCalculator;
    private final MemberLock memberLock;

    public RewardsService(LedgerEntryRepository ledger, PointsCalculator pointsCalculator, MemberLock memberLock) {
        this.ledger = ledger;
        this.pointsCalculator = pointsCalculator;
        this.memberLock = memberLock;
    }

    /**
     * @return true if points were awarded, false if this event was already processed
     */
    public boolean awardPoints(OrderPlacedEvent event) {
        if (ledger.existsByEventId(event.eventId())) {
            log.info("Duplicate event ignored: eventId={} orderId={}", event.eventId(), event.orderId());
            return false;
        }

        long points = pointsCalculator.calculate(event.amount(), event.category());

        LedgerEntry entry = LedgerEntry.earn(
                event.eventId(),
                event.memberId(),
                event.orderId(),
                points,
                "Order %s (%s, Rs %s)".formatted(event.orderId(), event.category(), event.amount()));

        try {
            // A single INSERT is its own transaction, so there is nothing to roll back by hand.
            // saveAndFlush sends the INSERT now, so a constraint violation surfaces right here.
            ledger.saveAndFlush(entry);
        } catch (DataIntegrityViolationException e) {
            // Lost the race: another thread inserted the same eventId between our check and
            // our insert. Confirm that is really the reason before swallowing the error.
            if (ledger.existsByEventId(event.eventId())) {
                log.info("Duplicate event ignored (caught by unique constraint): eventId={}", event.eventId());
                return false;
            }
            throw e;
        }

        log.info("Awarded {} points to member {} for order {} (eventId={})",
                points, event.memberId(), event.orderId(), event.eventId());
        return true;
    }

    /** @throws MemberNotFoundException if the member has no ledger rows */
    @Transactional(readOnly = true)
    public long getBalance(String memberId) {
        requireKnownMember(memberId);
        return ledger.sumPointsByMemberId(memberId);
    }

    /** @return the member's ledger rows, newest first */
    @Transactional(readOnly = true)
    public List<LedgerEntry> getHistory(String memberId) {
        requireKnownMember(memberId);
        return ledger.findByMemberIdOrderByCreatedAtDescIdDesc(memberId);
    }

    /**
     * Spends points. Everything below runs in ONE transaction: take the member's lock,
     * read the balance, insert the REDEEM row. If any step fails, nothing is saved.
     *
     * @param points how many points to spend (positive)
     * @return the balance after the redemption
     * @throws MemberNotFoundException      if the member has no ledger rows
     * @throws InsufficientBalanceException if points is more than the current balance
     */
    @Transactional
    public long redeem(String memberId, long points) {
        if (points <= 0) {
            throw new IllegalArgumentException("points to redeem must be greater than 0");
        }

        // Without this lock, two simultaneous redemptions could both see the old balance
        // and overspend. See MemberLock.
        memberLock.lock(memberId);

        requireKnownMember(memberId);
        long balance = ledger.sumPointsByMemberId(memberId);
        if (points > balance) {
            throw new InsufficientBalanceException(points, balance);
        }

        ledger.save(LedgerEntry.redeem(memberId, points, "Redeemed " + points + " points"));

        long newBalance = balance - points;
        log.info("Member {} redeemed {} points, balance is now {}", memberId, points, newBalance);
        return newBalance;
    }

    /** We have no members table: a member "exists" once they have at least one ledger row. */
    private void requireKnownMember(String memberId) {
        if (!ledger.existsByMemberId(memberId)) {
            throw new MemberNotFoundException(memberId);
        }
    }
}
