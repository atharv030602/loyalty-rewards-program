package com.loyalty.rewards.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

/**
 * Makes redemptions for the SAME member run one after another.
 *
 * The problem ("lost update" / double spend): a member has 100 points and sends two
 * "redeem 80" requests at the same moment. Both read balance = 100, both pass the check,
 * both insert -80 -> balance is now -60.
 *
 * The fix: a PostgreSQL advisory lock keyed on the member id. The second request waits
 * here until the first transaction commits, then reads the NEW balance (20) and is rejected.
 * "xact" = the lock is released automatically when the transaction ends, so it cannot leak.
 * Different members never wait for each other.
 */
@Component
public class MemberLock {

    private final JdbcTemplate jdbcTemplate;

    public MemberLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Must be called inside a transaction; blocks until the lock is free. */
    public void lock(String memberId) {
        // hashtext() turns the member id into the number the lock function needs.
        // The function returns nothing useful, so the row handler ignores the result.
        jdbcTemplate.query(
                "select pg_advisory_xact_lock(hashtext(?))",
                (RowCallbackHandler) resultSet -> { },
                memberId);
    }
}
