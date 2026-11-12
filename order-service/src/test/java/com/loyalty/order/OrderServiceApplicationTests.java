package com.loyalty.order;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: starts the whole Spring context.
 * If any bean is misconfigured, this fails - a cheap early warning.
 * The Kafka producer connects lazily (on the first send), so no broker is needed.
 */
// Do not try to create Kafka topics here: this test must pass with Docker switched off.
// (Real Kafka tests come in Phase 5 with Testcontainers.)
@SpringBootTest(properties = "spring.kafka.admin.auto-create=false")
class OrderServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
