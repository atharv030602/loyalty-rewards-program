package com.loyalty.rewards.api;

/** JSON body returned by POST /members/{id}/redeem. */
public record RedeemResponse(String memberId, long redeemedPoints, long balance) {
}
