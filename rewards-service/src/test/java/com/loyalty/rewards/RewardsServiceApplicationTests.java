package com.loyalty.rewards;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke test: starts the whole Spring context against a real PostgreSQL.
 *
 * This proves three things at once: the Flyway migration runs, the JPA entities match
 * the tables it created (ddl-auto=validate), and every bean can be wired.
 *
 * Testcontainers starts a throwaway PostgreSQL in Docker for this test and removes it
 * afterwards, so the test never touches the database from docker-compose.
 * (Docker Desktop must be running.)
 */
@SpringBootTest
@Testcontainers
class RewardsServiceApplicationTests {

    // @ServiceConnection: Spring Boot reads the container's random port, user and password
    // and uses them instead of the spring.datasource.* values in application.yml.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void contextLoads() {
    }
}
