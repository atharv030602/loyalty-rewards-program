package com.loyalty.rewards.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * JSON body of POST /members/{id}/redeem.
 * "Long" (not "long") so a missing field is null and @NotNull can report it,
 * instead of silently becoming 0.
 */
public record RedeemRequest(

        @NotNull(message = "points is required")
        @Positive(message = "points must be greater than 0")
        Long points) {
}
