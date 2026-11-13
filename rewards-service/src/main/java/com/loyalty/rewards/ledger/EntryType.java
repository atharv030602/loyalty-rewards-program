package com.loyalty.rewards.ledger;

/** What kind of points movement a ledger row represents. */
public enum EntryType {
    /** Points added because of an order. */
    EARN,
    /** Points spent by the member (used from Phase 4). */
    REDEEM
}
