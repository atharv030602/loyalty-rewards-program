package com.loyalty.rewards;

import org.junit.jupiter.api.Test;

/**
 * Smoke test: starts the whole Spring context against real PostgreSQL and Kafka.
 *
 * This proves three things at once: the Flyway migration runs, the JPA entities match
 * the tables it created (ddl-auto=validate), and every bean can be wired.
 */
class RewardsServiceApplicationTests extends IntegrationTestBase {

    @Test
    void contextLoads() {
    }
}
