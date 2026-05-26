package io.ekbatan.test.di;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Native (and packaged-jar) integration test for the Ekbatan Quarkus extension. The companion
 * {@link EkbatanQuarkusExtensionIntegrationTest} exercises the same wiring graph in JVM mode via
 * direct {@code @Inject}; this test exercises it through the packaged artifact via HTTP.
 *
 * <p>Driven through {@link EkbatanTestEndpoint} because {@code @QuarkusIntegrationTest} runs the
 * packaged binary out-of-process - {@code @Inject} cannot bridge the test JVM to the app process.
 * Same pattern as official Quarkus extension tests (Flyway, JPA-Postgres, etc.).
 *
 * <p>Run via:
 *
 * <ul>
 *   <li>{@code ./gradlew :ekbatan-integration-tests:di:quarkus:quarkusIntTest} - against the
 *       packaged jar.</li>
 *   <li>{@code ./gradlew :ekbatan-integration-tests:di:quarkus:testNative} - against the native
 *       binary.</li>
 * </ul>
 *
 * <p>Asserts the same end-to-end behavior as the JVM test: action execution persists a widget
 * via {@code SingleTableJsonEventPersister}, the {@code EventFanoutJob} writes notifications,
 * the {@code EventHandlingJob} drains them, and {@code WidgetCreatedCounterHandler} is invoked.
 */
@QuarkusIntegrationTest
@QuarkusTestResource(value = PostgresTestResource.class, restrictToAnnotatedClass = true)
class EkbatanQuarkusExtensionNativeIT {

    @Test
    void createAction_persists_widget_and_repository_can_fetch_it() {
        var id = given().contentType(ContentType.JSON)
                .body(new EkbatanTestEndpoint.CreateWidgetRequest("first widget", "blue"))
                .when()
                .post("/test/widgets")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("name", equalTo("first widget"))
                .body("color", equalTo("blue"))
                .body("state", equalTo("ACTIVE"))
                .body("version", equalTo(1))
                .extract()
                .path("id");

        given().when()
                .get("/test/widgets/{id}", id)
                .then()
                .statusCode(200)
                .body("name", equalTo("first widget"))
                .body("color", equalTo("blue"))
                .body("state", equalTo("ACTIVE"))
                .body("version", equalTo(1));
    }

    @Test
    void event_handler_is_invoked_after_widget_is_created_via_action() {
        int beforeCount = given().when()
                .get("/test/handler-state")
                .then()
                .statusCode(200)
                .extract()
                .path("callCount");

        given().contentType(ContentType.JSON)
                .body(new EkbatanTestEndpoint.CreateWidgetRequest("handled widget", "red"))
                .when()
                .post("/test/widgets")
                .then()
                .statusCode(200);

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    int callCount = given().when()
                            .get("/test/handler-state")
                            .then()
                            .statusCode(200)
                            .extract()
                            .path("callCount");
                    List<String> names = given().when()
                            .get("/test/handler-state")
                            .then()
                            .statusCode(200)
                            .extract()
                            .path("handledNames");
                    assertThat(callCount).isEqualTo(beforeCount + 1);
                    assertThat(names).contains("handled widget");
                });
    }
}
