package io.example.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * End-to-end integration test for the wallet transfer saga. Two scenarios:
 *
 * <ol>
 *   <li><b>Happy path</b> — transfer 25 from A (balance 100) to B (balance 0). The saga's three
 *       steps (InitiateTransfer → CompleteTransfer) run via the local-event-handler; both
 *       wallets converge to A = 75, B = 25.</li>
 *   <li><b>Compensation path</b> — transfer 25 from A to a <em>closed</em> wallet B. The saga
 *       reaches step 2, can't credit the closed destination, emits TransferFailed on the source,
 *       and the failed handler invokes RefundTransferAction. A returns to its original balance.</li>
 * </ol>
 *
 * <p>The saga is asynchronous from the caller's perspective — the REST endpoint returns 202
 * after step 1 commits. Awaitility waits for the chain to converge.
 */
@SpringBootTest(
        classes = {Application.class, TestcontainersConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ekbatan.namespace=test.wallet.saga",
            "ekbatan.sharding.defaultShard.group=0",
            "ekbatan.sharding.defaultShard.member=0",
            "ekbatan.sharding.groups[0].group=0",
            "ekbatan.sharding.groups[0].name=default",
            "ekbatan.sharding.groups[0].members[0].member=0",
            "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.maximumPoolSize=5",
            "ekbatan.sharding.groups[0].members[0].configs.jobsConfig.maximumPoolSize=4",
            // Tighten poll intervals so the saga's event chain converges quickly under test.
            "ekbatan.local-event-handler.fanoutPollDelay=200ms",
            "ekbatan.local-event-handler.handlingPollDelay=200ms",
            "ekbatan.jobs.pollingInterval=1s",
            "ekbatan.jobs.shutdownMaxWait=5s",
            "ekbatan.local-event-handler.handling.enabled=true",
        })
class WalletSagaIntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void happy_path_transfer_credits_destination_via_the_local_event_handler() throws Exception {
        // GIVEN — wallet A (balance 100 USD) and wallet B (balance 0 USD)
        final var aId = createWallet("USD", "100.00");
        final var bId = createWallet("USD", "0.00");

        // WHEN — request transfer 25 from A to B
        final var transferResponse = post(
                "/wallets/transfers",
                Map.of(
                        "fromWalletId", aId.toString(),
                        "toWalletId", bId.toString(),
                        "amount", "25.00"));

        // THEN — synchronous step 1 already committed (debit + TransferInitiated)
        assertThat(transferResponse.statusCode()).isEqualTo(202);
        assertThat(parse(transferResponse)).containsKey("transferId");
        // Source debited synchronously to 75
        @SuppressWarnings("unchecked")
        final var fromWalletAfterDebit =
                (Map<String, Object>) parse(transferResponse).get("fromWallet");
        assertThat(fromWalletAfterDebit).containsEntry("balance", 75.00);

        // AND — the local-event-handler chains step 2 (CompleteTransferAction) which credits B
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(parse(get("/wallets/" + bId))).containsEntry("balance", 25.00);
                    assertThat(parse(get("/wallets/" + aId))).containsEntry("balance", 75.00);
                });
    }

    @Test
    void compensation_path_refunds_source_when_destination_is_closed() throws Exception {
        // GIVEN — wallet A (balance 100 USD) and a CLOSED wallet B
        final var aId = createWallet("USD", "100.00");
        final var bId = createWallet("USD", "0.00");
        final var closeResponse = post("/wallets/" + bId + "/close", Map.of());
        assertThat(closeResponse.statusCode()).isEqualTo(200);
        assertThat(parse(closeResponse)).containsEntry("state", "CLOSED");

        // WHEN — try to transfer 25 from A to the closed B
        final var transferResponse = post(
                "/wallets/transfers",
                Map.of(
                        "fromWalletId", aId.toString(),
                        "toWalletId", bId.toString(),
                        "amount", "25.00"));
        assertThat(transferResponse.statusCode()).isEqualTo(202);
        // Step 1 debited A synchronously to 75
        @SuppressWarnings("unchecked")
        final var fromWalletAfterDebit =
                (Map<String, Object>) parse(transferResponse).get("fromWallet");
        assertThat(fromWalletAfterDebit).containsEntry("balance", 75.00);

        // THEN — step 2 sees a CLOSED destination and emits TransferFailed.
        // TransferFailedEventHandler picks it up and runs RefundTransferAction, restoring A.
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(parse(get("/wallets/" + aId))).containsEntry("balance", 100.00);
                    // B should be unchanged — still CLOSED, balance 0
                    assertThat(parse(get("/wallets/" + bId))).containsEntry("balance", 0.00);
                    assertThat(parse(get("/wallets/" + bId))).containsEntry("state", "CLOSED");
                });
    }

    @Test
    void compensation_path_refunds_source_when_destination_does_not_exist() throws Exception {
        // GIVEN — only wallet A; destination wallet does not exist
        final var aId = createWallet("USD", "100.00");
        final var nonExistentBId = UUID.randomUUID();

        // WHEN
        final var transferResponse = post(
                "/wallets/transfers",
                Map.of(
                        "fromWalletId", aId.toString(),
                        "toWalletId", nonExistentBId.toString(),
                        "amount", "25.00"));
        assertThat(transferResponse.statusCode()).isEqualTo(202);

        // THEN — same compensation outcome, restored balance on source
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(parse(get("/wallets/" + aId))).containsEntry("balance", 100.00));
    }

    private UUID createWallet(String currency, String initialBalance) throws Exception {
        final var response = post(
                "/wallets",
                Map.of(
                        "ownerId", UUID.randomUUID().toString(),
                        "currency", currency,
                        "initialBalance", initialBalance));
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString((String) parse(response).get("id"));
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
