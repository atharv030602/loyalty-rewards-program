package com.loyalty.rewards.points;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Turns an order into loyalty points.
 *
 * Rules:
 * - 1 point for every FULL Rs 100 spent (Rs 250 -> 2 points, Rs 99.99 -> 0 points)
 * - category TRAVEL earns 2x, DINING earns 3x, every other category earns 1x
 * - the multiplier is applied AFTER rounding down (Rs 250 TRAVEL -> 2 x 2 = 4 points)
 * - a null or negative amount is a programming error -> IllegalArgumentException
 *
 * This class has no Spring or database dependencies on purpose: pure logic like this
 * is the easiest code to unit-test.
 */
@Component
public class PointsCalculator {

    /**
     * @param amount   order amount in INR
     * @param category spend category from the event, e.g. "TRAVEL"
     * @return points earned, never negative
     */
    public long calculate(BigDecimal amount, String category) {
        // TODO (Atharv): your turn - make PointsCalculatorTest pass.
        throw new UnsupportedOperationException("PointsCalculator.calculate is not implemented yet");
    }
}
