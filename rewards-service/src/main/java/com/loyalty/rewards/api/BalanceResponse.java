package com.loyalty.rewards.api;

/** JSON body of GET /members/{id}/balance. */
public record BalanceResponse(String memberId, long balance) {
}
