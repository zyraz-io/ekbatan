package io.example.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.example.wallet.model.NotificationKind;
import io.example.wallet.repository.NotificationRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * End-to-end integration test for the wallet REST endpoints. {@link TestcontainersConfiguration}
 * brings up the testcontainer and publishes its JDBC coords as {@code ekbatan.sharding.*}
 * properties via {@code DynamicPropertyRegistrar}. Spring Boot's {@code FlywayAutoConfiguration}
 * then runs migrations at context startup; {@code EkbatanShardFlywayCustomizer} overrides the
 * Flyway dataSource from the same {@code ekbatan.sharding.*} block, so connection coordinates
 * have a single source of truth.
 */
@SpringBootTest(
        classes = {Application.class, TestcontainersConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // Single-shard sharding skeleton - container-dependent jdbcUrl/username/password
            // come from the DynamicPropertyRegistrar in TestcontainersConfiguration.
            "ekbatan.namespace=test.wallet",
            "ekbatan.sharding.defaultShard.group=0",
            "ekbatan.sharding.defaultShard.member=0",
            "ekbatan.sharding.groups[0].group=0",
            "ekbatan.sharding.groups[0].name=default",
            "ekbatan.sharding.groups[0].members[0].member=0",
            "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.maximumPoolSize=5",
            "ekbatan.sharding.groups[0].members[0].configs.jobsConfig.maximumPoolSize=4",
            // Tighten poll intervals so the listen-to-yourself path doesn't sit idle for default waits.
            "ekbatan.local-event-handler.fanoutPollDelay=200ms",
            "ekbatan.local-event-handler.handlingPollDelay=200ms",
            "ekbatan.jobs.pollingInterval=1s",
            "ekbatan.jobs.shutdownMaxWait=5s",
            // @ConditionalOnProperty is evaluated before DynamicPropertyRegistrar runs, so the
            // local-event-handler opt-in lives here in @SpringBootTest(properties=).
            "ekbatan.local-event-handler.handling.enabled=true",
        })
class WalletControllerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private NotificationRepository notificationRepository;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deposit_emits_event_and_handler_creates_notification() throws Exception {
        // GIVEN - a freshly created wallet
        final var ownerId = UUID.randomUUID();
        final var createResponse =
                post("/wallets", Map.of("ownerId", ownerId.toString(), "currency", "USD", "initialBalance", "0.00"));
        assertThat(createResponse.statusCode()).isEqualTo(201);
        final var walletId = UUID.fromString((String) parse(createResponse).get("id"));

        // WHEN - deposit $100 with a notification recipient
        final var depositResponse = post(
                "/wallets/" + walletId + "/deposits", Map.of("amount", "100.00", "recipient", "alice@example.com"));

        // THEN - the synchronous response reflects the new balance
        assertThat(depositResponse.statusCode()).isEqualTo(200);
        assertThat(parse(depositResponse)).containsEntry("balance", 100.00);

        // AND - the listen-to-yourself handler eventually creates the Notification row.
        // The fan-out + handling jobs poll asynchronously, so we wait until the row appears.
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    final var notifications = notificationRepository.findAllByWalletId(walletId);
                    assertThat(notifications).hasSize(1);
                    assertThat(notifications.getFirst().kind).isEqualTo(NotificationKind.MONEY_DEPOSITED);
                    assertThat(notifications.getFirst().recipient).isEqualTo("alice@example.com");
                    assertThat(notifications.getFirst().message).contains("100");
                });
    }

    @Test
    void close_transitions_wallet_to_closed_state() throws Exception {
        // GIVEN - a freshly created wallet
        final var ownerId = UUID.randomUUID();
        final var createResponse =
                post("/wallets", Map.of("ownerId", ownerId.toString(), "currency", "EUR", "initialBalance", "0.00"));
        final var walletId = UUID.fromString((String) parse(createResponse).get("id"));

        // WHEN
        final var closeResponse = post("/wallets/" + walletId + "/close", Map.of());

        // THEN
        assertThat(closeResponse.statusCode()).isEqualTo(200);

        // AND
        assertThat(parse(get("/wallets/" + walletId))).containsEntry("state", "CLOSED");
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(HttpResponse<String> response) throws Exception {
        return objectMapper.readValue(response.body(), Map.class);
    }
}
