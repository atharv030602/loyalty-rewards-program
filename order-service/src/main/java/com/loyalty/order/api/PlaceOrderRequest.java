package com.loyalty.order.api;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * JSON body of POST /orders.
 *
 * The annotations are Bean Validation rules. They are checked only because the
 * controller parameter is marked {@code @Valid}; a failed rule becomes 400 Bad Request
 * before our own code runs.
 *
 * Money is {@link BigDecimal}, never double: double cannot store 0.1 exactly,
 * and rounding errors in money are bugs.
 */
public record PlaceOrderRequest(

        @NotBlank(message = "orderId is required")
        @Size(max = 64, message = "orderId must be at most 64 characters")
        String orderId,

        @NotBlank(message = "memberId is required")
        @Size(max = 64, message = "memberId must be at most 64 characters")
        String memberId,

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than 0")
        @Digits(integer = 10, fraction = 2, message = "amount can have at most 2 decimal places")
        BigDecimal amount,

        @NotNull(message = "category is required")
        OrderCategory category) {
}
