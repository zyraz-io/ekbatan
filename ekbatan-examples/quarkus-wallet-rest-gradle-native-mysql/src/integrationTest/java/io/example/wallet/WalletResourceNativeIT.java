package io.example.wallet;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Out-of-process integration test for the wallet REST endpoints, exercising the packaged
 * artifact (JAR or native binary) via HTTP. The JVM-mode counterpart lives in the
 * sibling {@code quarkus-wallet-rest-<gradle|maven>-<db>} project (no -native suffix),
 * which uses {@code @QuarkusTest} + {@code @Inject} to additionally assert internal
 * state (e.g. notification rows written by the listen-to-yourself fan-out).
 *
 * <p>{@code @QuarkusIntegrationTest} launches the packaged binary out-of-process — {@code @Inject}
 * fields can't bridge the test JVM to the app process. Every assertion goes through the REST
 * surface, with REST-assured making the HTTP calls. This is the same pattern Quarkus's own
 * extension tests follow.
 *
 * <p>Run via:
 *
 * <ul>
 *   <li>{@code ./gradlew quarkusIntTest} — against the packaged jar.</li>
 *   <li>{@code ./gradlew quarkusIntTest -Dquarkus.native.enabled=true} — against the native binary.</li>
 * </ul>
 *
 * <p>Notification verification is omitted here (the listen-to-yourself path is asserted in the
 * JVM test, which can inspect {@code NotificationRepository} directly). This test just confirms
 * the REST flow works end-to-end through the packaged artifact.
 */
@QuarkusIntegrationTest
@QuarkusTestResource(value = MySQLTestResource.class, restrictToAnnotatedClass = true)
class WalletResourceNativeIT {

    @Test
    void create_then_deposit_returns_updated_balance() {
        // GIVEN — a freshly created wallet
        final var ownerId = UUID.randomUUID();
        final var walletId = UUID.fromString(given().contentType(ContentType.JSON)
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

        // WHEN — deposit $100
        given().contentType(ContentType.JSON)
                .body(Map.of("amount", "100.00", "recipient", "alice@example.com"))
                .when()
                .post("/wallets/" + walletId + "/deposits")
                .then()
                .statusCode(200)
                .body("balance", equalTo(100.00f));

        // THEN — GET returns the updated balance
        given().when().get("/wallets/" + walletId).then().statusCode(200).body("balance", equalTo(100.00f));
    }

    @Test
    void close_transitions_wallet_to_closed_state() {
        final var ownerId = UUID.randomUUID();
        final var walletId = UUID.fromString(given().contentType(ContentType.JSON)
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

        given().contentType(ContentType.JSON)
                .when()
                .post("/wallets/" + walletId + "/close")
                .then()
                .statusCode(200);

        given().when().get("/wallets/" + walletId).then().statusCode(200).body("state", equalTo("CLOSED"));
    }
}
