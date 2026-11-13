package com.loyalty.rewards;

import com.loyalty.rewards.points.PointsCalculator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Parent of every integration test: the whole application on a random HTTP port,
 * with real PostgreSQL and Kafka (see TestcontainersConfiguration).
 *
 * All integration tests share this exact setup on purpose. Spring caches a test context
 * by its configuration, so the application and both containers start ONCE for all of them.
 * (Docker Desktop must be running.)
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        // Retry quickly in tests: 3 retries, 100 ms apart, instead of 1 second.
        properties = "app.kafka.retry.interval-ms=100")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTestBase {

    /**
     * A "spy" is the real calculator wrapped by Mockito: it behaves normally unless a test
     * tells it to fail for a specific input. That is how we simulate a processing failure.
     */
    @MockitoSpyBean
    protected PointsCalculator pointsCalculator;
}
