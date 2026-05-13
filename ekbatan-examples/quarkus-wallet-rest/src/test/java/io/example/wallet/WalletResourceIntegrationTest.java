package io.example.wallet;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.example.wallet.entity.NotificationKind;
import io.example.wallet.repository.NotificationRepository;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test for the wallet REST endpoints. {@link MariaDBTestResource} brings
 * up the MariaDB testcontainer and runs Flyway migrations before the Quarkus app boots.
 */
@QuarkusTest
@QuarkusTestResource(value = MariaDBTestResource.class, restrictToAnnotatedClass = true)
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
