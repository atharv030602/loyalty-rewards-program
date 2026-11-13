package com.loyalty.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end test of the whole platform, as a black box.
 *
 * It starts the two REAL jars ("java -jar ...", exactly what would be deployed) as separate
 * processes, plus a throwaway Kafka and PostgreSQL in Docker. Then it talks to the services
 * only through HTTP, like a real client:
 *
 *   POST /orders (order-service) -> Kafka -> rewards-service -> PostgreSQL -> GET /balance
 *
 * Nothing is mocked. If this passes, the two services really work together.
 * Run with "mvn verify" (Docker Desktop must be running).
 */
@Testcontainers
class LoyaltyEndToEndIT {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(60);
    private static final String MEMBER = "M-42";

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper json = new ObjectMapper();
    private static final List<Process> processes = new ArrayList<>();

    private static String orderService;    // e.g. http://localhost:51234
    private static String rewardsService;

    @BeforeAll
    static void startBothServices() throws Exception {
        int orderPort = freePort();
        int rewardsPort = freePort();
        orderService = "http://localhost:" + orderPort;
        rewardsService = "http://localhost:" + rewardsPort;

        // Command-line arguments override application.yml, so the jars are unchanged
        // but point at the test containers instead of docker-compose.
        startJar("rewards-service", System.getProperty("rewards.service.jar"),
                "--server.port=" + rewardsPort,
                "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword());
        startJar("order-service", System.getProperty("order.service.jar"),
                "--server.port=" + orderPort,
                "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers());

        awaitHealthy("rewards-service", rewardsService);
        awaitHealthy("order-service", orderService);
    }

    @AfterAll
    static void stopBothServices() {
        processes.forEach(Process::destroyForcibly);
    }

    @Test
    void ordersEarnPointsThatCanBeRedeemed() throws Exception {
        // 1. First order: Rs 2500 TRAVEL = 25 x 2 = 50 points.
        assertThat(placeOrder("ORD-1", "2500", "TRAVEL").statusCode()).isEqualTo(202);
        awaitBalance(50);

        // 2. Second order for the SAME member: Rs 1000 DINING = 10 x 3 = 30 points.
        //    This is the exact case that went missing in manual testing; the balance must reach 80.
        assertThat(placeOrder("ORD-2", "1000", "DINING").statusCode()).isEqualTo(202);
        awaitBalance(80);

        // 3. The client retries ORD-2 (same orderId). It is accepted again, but must add nothing.
        //    ORD-3 (Rs 100 GROCERY = 1 point) is sent after it: same member -> same partition ->
        //    processed after the duplicate. So when the balance is 81, the duplicate has been
        //    handled, and 81 (not 111) proves it was ignored.
        assertThat(placeOrder("ORD-2", "1000", "DINING").statusCode()).isEqualTo(202);
        assertThat(placeOrder("ORD-3", "100", "GROCERY").statusCode()).isEqualTo(202);
        awaitBalance(81);

        // 4. History lists the three orders, newest first, and ORD-2 only once.
        JsonNode history = body(get(rewardsService + "/members/" + MEMBER + "/history"));
        assertThat(history).hasSize(3);
        assertThat(history.get(0).get("orderId").asText()).isEqualTo("ORD-3");
        assertThat(history.get(1).get("orderId").asText()).isEqualTo("ORD-2");
        assertThat(history.get(1).get("points").asLong()).isEqualTo(30);
        assertThat(history.get(2).get("orderId").asText()).isEqualTo("ORD-1");
        assertThat(history.get(2).get("points").asLong()).isEqualTo(50);

        // 5. Redeem 30 points -> balance 51.
        HttpResponse<String> redeemed = post(rewardsService + "/members/" + MEMBER + "/redeem", "{\"points\":30}");
        assertThat(redeemed.statusCode()).isEqualTo(200);
        assertThat(body(redeemed).get("balance").asLong()).isEqualTo(51);

        // 6. Redeeming more than the balance is rejected with a clear error and changes nothing.
        HttpResponse<String> rejected = post(rewardsService + "/members/" + MEMBER + "/redeem", "{\"points\":1000}");
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(body(rejected).get("detail").asText())
                .isEqualTo("Insufficient balance: requested 1000 points, available 51");
        assertThat(currentBalance()).isEqualTo(51);
    }

    @Test
    void invalidOrderIsRejectedAndEarnsNothing() throws Exception {
        HttpResponse<String> response = post(orderService + "/orders",
                "{\"orderId\":\"ORD-X\",\"memberId\":\"M-INVALID\",\"amount\":-5,\"category\":\"TRAVEL\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body(response).get("errors").get("amount").asText()).isEqualTo("amount must be greater than 0");
        // The member never earned anything, so rewards-service does not know them.
        assertThat(get(rewardsService + "/members/M-INVALID/balance").statusCode()).isEqualTo(404);
    }

    // ---------- helpers ----------

    private static HttpResponse<String> placeOrder(String orderId, String amount, String category) throws Exception {
        return post(orderService + "/orders",
                "{\"orderId\":\"%s\",\"memberId\":\"%s\",\"amount\":%s,\"category\":\"%s\"}"
                        .formatted(orderId, MEMBER, amount, category));
    }

    /** Points are awarded asynchronously, so keep asking until the balance is right (or time out). */
    private static void awaitBalance(long expected) {
        await().atMost(EVENT_TIMEOUT).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(currentBalance()).isEqualTo(expected));
    }

    /** @return the balance, or -1 while the member is still unknown (404) */
    private static long currentBalance() throws Exception {
        HttpResponse<String> response = get(rewardsService + "/members/" + MEMBER + "/balance");
        return response.statusCode() == 200 ? body(response).get("balance").asLong() : -1;
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String url, String jsonBody) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode body(HttpResponse<String> response) throws IOException {
        return json.readTree(response.body());
    }

    private static void startJar(String name, String jarPath, String... args) throws IOException {
        Path jar = Path.of(jarPath);
        assertThat(Files.exists(jar))
                .as("%s jar not found at %s - run 'mvn verify' from the project root", name, jar)
                .isTrue();

        List<String> command = new ArrayList<>();
        // Use the same Java that is running this test.
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(List.of(args));

        // Each service writes its console output to e2e-tests/target/<name>.log.
        File log = Path.of(System.getProperty("e2e.log.dir", "target"), name + ".log").toFile();
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start();
        processes.add(process);
        // Safety net: stop the service even if the test JVM is killed.
        Runtime.getRuntime().addShutdownHook(new Thread(process::destroyForcibly));
    }

    private static void awaitHealthy(String name, String baseUrl) {
        await().alias(name + " did not become healthy - see e2e-tests/target/" + name + ".log")
                .atMost(STARTUP_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()   // "connection refused" while the service is still starting
                .untilAsserted(() -> {
                    HttpResponse<String> response = get(baseUrl + "/actuator/health");
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(body(response).get("status").asText()).isEqualTo("UP");
                });
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
