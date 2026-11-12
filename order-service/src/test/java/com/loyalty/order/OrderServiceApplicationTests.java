package com.loyalty.order;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: starts the whole Spring context.
 * If any bean is misconfigured, this fails - a cheap early warning.
 */
@SpringBootTest
class OrderServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
