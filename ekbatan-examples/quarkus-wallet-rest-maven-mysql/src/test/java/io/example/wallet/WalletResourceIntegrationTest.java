package io.example.wallet;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.example.wallet.model.NotificationKind;
import io.example.wallet.repository.NotificationRepository;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * In-process integration test for the wallet REST endpoints.
 *
 * <p>{@link MySQLTestResource} brings up the MySQL testcontainer and publishes
 * connection coordinates as runtime SmallRye Config properties before the Quarkus app boots;
 * the {@code quarkus-flyway} extension then runs migrations at app startup against the
 * datasource overridden by {@code EkbatanShardFlywayCustomizer} (which points at the
 * default shard's {@code primaryConfig} — single source of truth for connection coordinates).
 *
 * <p>Companion to {@code WalletResourceNativeIT} in {@code src/integrationTest}, which
 * runs the same REST flow against the packaged JAR (or native binary), out-of-process.
 * This test additionally asserts internal state via {@code @Inject NotificationRepository}
 * to verify the listen-to-yourself fan-out wrote a notification row — something the
 * out-of-process IT can't see.
 */
@QuarkusTest
@QuarkusTestResource(value = MySQLTestResource.class, restrictToAnnotatedClass = true)
class WalletResourceIntegrationTest {

    @Inject
    NotificationRepository notificationRepository;

    @Test
    void deposit_emits_event_and_handler_creates_notification() {
        // GIVEN — a freshly created wallet
        final var ownerId = UUID.randomUUID();
        final var walletId = UUID.fromString(given().contentType("application/json")
                .body(Map.of(
                        "ownerId", ownerId.toString(),
                        "currency", "USD",
                        "initialBalance", "0.00"))
                .when()
                .post("/wallets")
                .then()
                .statusCode(201)
                .body("ownerId", equalTo(ownerId.toString()))
                .body("state", equalTo("OPENED"))
                .body("id", notNullValue())
                .extract()
                .path("id"));

        // WHEN — deposit $100 with a notification recipient
        given().contentType("application/json")
                .body(Map.of("amount", "100.00", "recipient", "alice@example.com"))
                .when()
                .post("/wallets/" + walletId + "/deposits")
                .then()
                .statusCode(200)
                .body("balance", equalTo(100.00f));

        // THEN — GET returns the updated balance
        given().when().get("/wallets/" + walletId).then().statusCode(200).body("balance", equalTo(100.00f));

        // AND — the listen-to-yourself handler eventually creates the Notification row.
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
        // GIVEN — a freshly created wallet
        final var ownerId = UUID.randomUUID();
        final var walletId = UUID.fromString(given().contentType("application/json")
                .body(Map.of(
                        "ownerId", ownerId.toString(),
                        "currency", "EUR",
                        "initialBalance", "0.00"))
                .when()
                .post("/wallets")
                .then()
                .statusCode(201)
                .extract()
                .path("id"));

        // WHEN
        given().contentType("application/json")
                .when()
                .post("/wallets/" + walletId + "/close")
                .then()
                .statusCode(200);

        // THEN
        given().when().get("/wallets/" + walletId).then().statusCode(200).body("state", equalTo("CLOSED"));
    }
}
