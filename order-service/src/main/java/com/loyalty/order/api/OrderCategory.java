package com.loyalty.order.api;

/**
 * Spend categories an order can belong to.
 *
 * An enum means a typo like "TRAVLE" is rejected at the door with 400 Bad Request,
 * instead of silently earning the wrong number of points later in rewards-service.
 */
public enum OrderCategory {
    TRAVEL,
    DINING,
    GROCERY,
    SHOPPING,
    OTHER
}
