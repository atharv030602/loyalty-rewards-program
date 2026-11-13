package com.loyalty.rewards.ledger;

import com.loyalty.rewards.event.OrderPlacedEvent;
import com.loyalty.rewards.points.PointsCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Business logic: award points for an order, exactly once per event.
 *
 * Kafka delivers "at least once": after a crash or a rebalance the same message can
 * arrive again. So the consumer must be IDEMPOTENT - processing an event twice must
 * leave the ledger exactly as if it was processed once.
 *
 * Two layers of protection:
 * 1. A quick check: "have we stored this eventId already?" - handles the normal duplicate.
 * 2. The UNIQUE constraint on event_id - handles the race where two threads pass
 *    check 1 at the same moment. The database lets only one insert win.
 */
@Service
public class RewardsService {

    private static final Logger log = LoggerFactory.getLogger(RewardsService.class);

    private final LedgerEntryRepository ledger;
    private final PointsCalculator pointsCalculator;

    public RewardsService(LedgerEntryRepository ledger, PointsCalculator pointsCalculator) {
        this.ledger = ledger;
        this.pointsCalculator = pointsCalculator;
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
}
