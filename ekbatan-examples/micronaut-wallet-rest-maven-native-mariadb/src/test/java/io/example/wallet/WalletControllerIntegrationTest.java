package io.example.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.example.wallet.model.NotificationKind;
import io.example.wallet.repository.NotificationRepository;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.mariadb.MariaDBContainer;

/**
 * End-to-end integration test for the wallet REST endpoints under Micronaut + MariaDB.
 *
 * <p>{@link TestPropertyProvider#getProperties()} runs <em>before</em> the Micronaut application
 * context is built, so the testcontainer is up by the time {@code EkbatanCoreConfiguration} resolves
 * {@code ekbatan.sharding.*}. {@code micronaut-flyway} then runs migrations on startup against
 * {@code flyway.datasources.default} - {@code EkbatanShardFlywayCustomizer} overrides the dataSource
 * from the same {@code ekbatan.sharding.*} block, so connection coordinates have a single source
 * of truth. Works identically on JVM and under substrate-VM (no FlywayHelper needed).
 *
 * <p>The {@code mariadb_init.sql} bind-mount is what the V0000 migration needs: it grants
 * cross-database privileges to the {@code wallet} user so {@code CREATE DATABASE eventlog}
 * succeeds at Flyway's first-migration phase. The script runs as root on container startup,
 * before the named user connects.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Property(name = "ekbatan.namespace", value = "test.wallet")
@Property(name = "ekbatan.local-event-handler.handling.enabled", value = "true")
@Property(name = "ekbatan.local-event-handler.fanout-poll-delay", value = "PT0.2S")
@Property(name = "ekbatan.local-event-handler.handling-poll-delay", value = "PT0.2S")
@Property(name = "ekbatan.jobs.polling-interval", value = "PT1S")
@Property(name = "ekbatan.jobs.shutdown-max-wait", value = "PT5S")
class WalletControllerIntegrationTest implements TestPropertyProvider {

    private final MariaDBContainer mariadb = new MariaDBContainer("mariadb:11.8")
            .withDatabaseName("wallet")
            .withUsername("wallet")
            .withPassword("wallet")
            .withEnv("TZ", "UTC")
            .withCopyToContainer(
                    Transferable.of("""
                                    -- The named test user (`wallet`) only has rights on the `wallet` database by default. The
                                    -- first Flyway migration needs to CREATE DATABASE eventlog and then write tables into it, so
                                    -- we grant cross-database privileges here. This script runs as root, before the container
                                    -- becomes ready, so subsequent migrations run as `wallet` with full access.
                                    GRANT ALL PRIVILEGES ON *.* TO 'wallet'@'%';
                                    FLUSH PRIVILEGES;
                                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "/docker-entrypoint-initdb.d/mariadb_init.sql");

    @Inject
    EmbeddedServer server;

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Inject
    NotificationRepository notificationRepository;

    @Override
    public Map<String, String> getProperties() {
        mariadb.start();
        var props = new HashMap<String, String>();
        props.put("ekbatan.sharding.defaultShard.group", "0");
        props.put("ekbatan.sharding.defaultShard.member", "0");
        props.put("ekbatan.sharding.groups[0].group", "0");
        props.put("ekbatan.sharding.groups[0].name", "default");
        props.put("ekbatan.sharding.groups[0].members[0].member", "0");
        // Primary pool - the one Ekbatan uses for application traffic.
        props.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl", mariadb.getJdbcUrl());
        props.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username", mariadb.getUsername());
        props.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password", mariadb.getPassword());
        props.put(
                "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.driverClassName",
                "org.mariadb.jdbc.Driver");
        props.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.maximumPoolSize", "5");
        // Jobs pool - isolated from primary so polling load can't starve app traffic.
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.jdbcUrl", mariadb.getJdbcUrl());
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.username", mariadb.getUsername());
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.password", mariadb.getPassword());
        props.put(
                "ekbatan.sharding.groups[0].members[0].configs.jobsConfig.driverClassName", "org.mariadb.jdbc.Driver");
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.maximumPoolSize", "4");
        return props;
    }

    @Test
    void deposit_emits_event_and_handler_creates_notification() {
        // GIVEN - a freshly created wallet
        final var ownerId = UUID.randomUUID();
        final HttpResponse<Map> createResponse = httpClient
                .toBlocking()
                .exchange(
                        HttpRequest.POST(
                                "/wallets",
                                Map.of("ownerId", ownerId.toString(), "currency", "USD", "initialBalance", "0.00")),
                        Map.class);
        assertThat(createResponse.code()).isEqualTo(201);
        final var walletId = UUID.fromString((String) createResponse.body().get("id"));

        // WHEN - deposit $100 with a notification recipient
        final HttpResponse<Map> depositResponse = httpClient
                .toBlocking()
                .exchange(
                        HttpRequest.POST(
                                "/wallets/" + walletId + "/deposits",
                                Map.of("amount", "100.00", "recipient", "alice@example.com")),
                        Map.class);

        // THEN - the synchronous response reflects the new balance
        assertThat(depositResponse.code()).isEqualTo(200);
        assertThat(depositResponse.body()).containsEntry("balance", 100.00);

        // AND - the listen-to-yourself handler eventually creates the Notification row.
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
    void close_transitions_wallet_to_closed_state() {
        // GIVEN - a freshly created wallet
        final var ownerId = UUID.randomUUID();
        final HttpResponse<Map> createResponse = httpClient
                .toBlocking()
                .exchange(
                        HttpRequest.POST(
                                "/wallets",
                                Map.of("ownerId", ownerId.toString(), "currency", "EUR", "initialBalance", "0.00")),
                        Map.class);
        final var walletId = UUID.fromString((String) createResponse.body().get("id"));

        // WHEN
        final HttpResponse<Map> closeResponse = httpClient
                .toBlocking()
                .exchange(HttpRequest.POST("/wallets/" + walletId + "/close", Map.of()), Map.class);

        // THEN
        assertThat(closeResponse.code()).isEqualTo(200);
        // AND
        final HttpResponse<Map> getResponse =
                httpClient.toBlocking().exchange(HttpRequest.GET("/wallets/" + walletId), Map.class);
        assertThat(getResponse.body()).containsEntry("state", "CLOSED");
    }
}
