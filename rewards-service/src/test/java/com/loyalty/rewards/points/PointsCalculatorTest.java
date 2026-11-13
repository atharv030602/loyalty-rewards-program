package com.loyalty.rewards.points;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Written BEFORE the implementation (test-driven development):
 * red (these fail) -> green (simplest code that passes) -> refactor (clean up, still green).
 *
 * Plain JUnit, no Spring: the calculator is created with "new", so this runs in milliseconds.
 */
class PointsCalculatorTest {

    private final PointsCalculator calculator = new PointsCalculator();

    @ParameterizedTest(name = "Rs {0} in {1} earns {2} points")
    @CsvSource({
            // --- base rule: 1 point per full Rs 100 ---
            "100,     OTHER,    1",
            "2500,    GROCERY,  25",
            "250,     SHOPPING, 2",    // partial hundreds are dropped
            "99.99,   OTHER,    0",    // below Rs 100 earns nothing
            "199.99,  GROCERY,  1",
            "0,       OTHER,    0",
            // --- TRAVEL: 2x ---
            "100,     TRAVEL,   2",
            "2500,    TRAVEL,   50",
            "250,     TRAVEL,   4",    // round down first (2), then multiply -> 4, not 5
            "99.99,   TRAVEL,   0",
            // --- DINING: 3x ---
            "100,     DINING,   3",
            "1000,    DINING,   30",
            "199.99,  DINING,   3",    // round down first (1), then multiply -> 3, not 5
            // --- a category we do not know earns the normal 1x ---
            "500,     ELECTRONICS, 5",
    })
    void calculatesPoints(BigDecimal amount, String category, long expectedPoints) {
        assertThat(calculator.calculate(amount, category)).isEqualTo(expectedPoints);
    }

    @Test
    void missingCategoryEarnsNormalRate() {
        assertThat(calculator.calculate(new BigDecimal("500"), null)).isEqualTo(5);
    }

    @Test
    void largeAmountDoesNotOverflow() {
        // Rs 9,999,999,999.99 is the largest amount order-service accepts.
        assertThat(calculator.calculate(new BigDecimal("9999999999.99"), "DINING"))
                .isEqualTo(299_999_997L);
    }

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> calculator.calculate(new BigDecimal("-100"), "TRAVEL"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingAmount() {
        assertThatThrownBy(() -> calculator.calculate(null, "TRAVEL"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
