package com.loyalty.rewards.ledger;

/** The member has no ledger rows at all, so we do not know them. Mapped to HTTP 404. */
public class MemberNotFoundException extends RuntimeException {

    public MemberNotFoundException(String memberId) {
        super("Member " + memberId + " was not found");
    }
}
