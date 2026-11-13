package com.loyalty.rewards.ledger;

/** The member tried to redeem more points than they have. Mapped to HTTP 409. */
public class InsufficientBalanceException extends RuntimeException {

    private final long requested;
    private final long available;

    public InsufficientBalanceException(long requested, long available) {
        super("Insufficient balance: requested " + requested + " points, available " + available);
        this.requested = requested;
        this.available = available;
    }

    public long getRequested() {
        return requested;
    }

    public long getAvailable() {
        return available;
    }
}
