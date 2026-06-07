package io.example.wallet;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
@QuarkusTestResource(value = MySQLTestResource.class, restrictToAnnotatedClass = true)
class WalletResourceNativeIT {

    @Test
    void separate_wallets_route_to_their_own_shards() {
        final var globalId = createWallet("US", "USD", "10.00", 0);
        final var mexicoId = createWallet("MX", "MXN", "20.00", 1);

        given().contentType(ContentType.JSON)
                .body(Map.of("amount", "5.00", "recipient", "global@example.com"))
                .when()
                .post("/wallets/" + globalId + "/deposits")
                .then()
                .statusCode(200)
                .body("balance", equalTo(15.00f))
                .body("shardGroup", equalTo(0));

        given().contentType(ContentType.JSON)
                .body(Map.of("amount", "7.00", "recipient", "mexico@example.com"))
                .when()
                .post("/wallets/" + mexicoId + "/deposits")
                .then()
                .statusCode(200)
                .body("balance", equalTo(27.00f))
                .body("shardGroup", equalTo(1));

        awaitNotification(mexicoId, "mexico@example.com");
    }

    @Test
    void close_transitions_wallet_to_closed_state() {
        final var walletId = createWallet("US", "EUR", "0.00", 0);

        given().contentType(ContentType.JSON)
                .when()
                .post("/wallets/" + walletId + "/close")
                .then()
                .statusCode(200);

        given().when().get("/wallets/" + walletId).then().statusCode(200).body("state", equalTo("CLOSED"));
    }

    private static void awaitNotification(UUID walletId, String recipient) {
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertNotificationExists(walletId, recipient));
    }

    private static void assertNotificationExists(UUID walletId, String recipient) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        System.getProperty("wallet.test.notification.jdbc-url"),
                        System.getProperty("wallet.test.notification.username"),
                        System.getProperty("wallet.test.notification.password"));
                var statement =
                        connection.prepareStatement("SELECT wallet_id, kind, recipient, message FROM notifications")) {
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    if (!walletId.toString().equalsIgnoreCase(String.valueOf(resultSet.getObject("wallet_id")))) {
                        continue;
                    }

                    assertEquals("MONEY_DEPOSITED", resultSet.getString("kind"));
                    assertEquals(recipient, resultSet.getString("recipient"));
                    if (!resultSet.getString("message").contains("Deposit of")) {
                        throw new AssertionError("Expected notification message to describe a deposit");
                    }
                    return;
                }
            }
        }

        throw new AssertionError("No notification found for wallet " + walletId);
    }

    private static UUID createWallet(String countryCode, String currency, String initialBalance, int expectedGroup) {
        return UUID.fromString(given().contentType(ContentType.JSON)
                .body(Map.of(
                        "countryCode", countryCode,
                        "ownerId", UUID.randomUUID().toString(),
                        "currency", currency,
                        "initialBalance", initialBalance))
                .when()
                .post("/wallets")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("shardGroup", equalTo(expectedGroup))
                .body("shardMember", equalTo(0))
                .extract()
                .path("id"));
    }
}
