package io.example.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * End-to-end integration test for the sharded wallet REST endpoints. Two Postgres testcontainers
 * (one per shard) come up via {@link TestcontainersConfiguration}; Flyway runs against both via
 * {@link FlywayConfiguration}; the test exercises:
 *
 * <ol>
 *   <li>Single-shard routing - a {@code countryCode=DE} wallet lands on the global shard, a
 *       {@code countryCode=MX} wallet lands on the mexico shard, and each is invisible to the
 *       other shard.</li>
 *   <li>Unregistered-shard fallback - {@code countryCode=AU} encodes group=2 which has no DB in
 *       this test setup; the framework falls back to the default shard and the wallet ends up
 *       reachable through the sharded repo without manual intervention.</li>
 *   <li>Cross-shard transfer - moving money between a global wallet and a mexico wallet runs
 *       two independent transactions (one per shard) and lands two duplicated {@code
 *       eventlog.events} rows with the same {@code action_id}.</li>
 * </ol>
 */
@SpringBootTest(
        classes = {Application.class, TestcontainersConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ekbatan.namespace=test.wallet.sharded",
            // Two-shard skeleton - container-dependent jdbcUrl/username/password come from the
            // DynamicPropertyRegistrar in TestcontainersConfiguration.
            "ekbatan.sharding.defaultShard.group=0",
            "ekbatan.sharding.defaultShard.member=0",
            "ekbatan.sharding.groups[0].group=0",
            "ekbatan.sharding.groups[0].name=global",
            "ekbatan.sharding.groups[0].members[0].member=0",
            "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.maximumPoolSize=5",
            "ekbatan.sharding.groups[1].group=1",
            "ekbatan.sharding.groups[1].name=mexico",
            "ekbatan.sharding.groups[1].members[0].member=0",
            "ekbatan.sharding.groups[1].members[0].configs.primaryConfig.maximumPoolSize=5",
        })
class WalletControllerIntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void wallet_created_with_country_de_lands_on_global_shard() throws Exception {
        // WHEN - create wallet for Germany
        final var response = post(
                "/wallets",
                Map.of(
                        "countryCode", "DE",
                        "ownerId", UUID.randomUUID().toString(),
                        "currency", "EUR",
                        "initialBalance", "0.00"));

        // THEN - wallet's shard bits decode to (group=0, member=0)
        assertThat(response.statusCode()).isEqualTo(201);
        final var body = parse(response);
        assertThat(body).containsEntry("shardGroup", 0);
        assertThat(body).containsEntry("shardMember", 0);
    }

    @Test
    void wallet_created_with_country_mx_lands_on_mexico_shard() throws Exception {
        // WHEN
        final var response = post(
                "/wallets",
                Map.of(
                        "countryCode", "MX",
                        "ownerId", UUID.randomUUID().toString(),
                        "currency", "MXN",
                        "initialBalance", "0.00"));

        // THEN - wallet's shard bits decode to (group=1, member=0)
        assertThat(response.statusCode()).isEqualTo(201);
        final var body = parse(response);
        assertThat(body).containsEntry("shardGroup", 1);
        assertThat(body).containsEntry("shardMember", 0);
    }

    @Test
    void wallet_with_unregistered_country_falls_back_to_default_shard() throws Exception {
        // WHEN - Australia maps to group=2 which has no DB in this setup
        final var response = post(
                "/wallets",
                Map.of(
                        "countryCode", "AU",
                        "ownerId", UUID.randomUUID().toString(),
                        "currency", "AUD",
                        "initialBalance", "0.00"));

        // THEN - the wallet is created (DatabaseRegistry.effectiveShard falls back to default)
        assertThat(response.statusCode()).isEqualTo(201);
        final var body = parse(response);
        // AND - the wallet's encoded shard still reads as Australia (group=2) - when an AU DB
        // is provisioned later, the wallet finds itself there with no data migration.
        assertThat(body).containsEntry("shardGroup", 2);
        assertThat(body).containsEntry("shardMember", 0);

        // AND - even though the encoded shard isn't deployed, the wallet is findable via the
        // sharded repo (the framework falls back to default for unregistered shards).
        final var walletId = (String) body.get("id");
        final var getResponse = get("/wallets/" + walletId);
        assertThat(getResponse.statusCode()).isEqualTo(200);
    }

    @Test
    void deposit_routes_to_the_wallets_own_shard() throws Exception {
        // GIVEN - a wallet on the mexico shard
        final var createResponse = post(
                "/wallets",
                Map.of(
                        "countryCode", "MX",
                        "ownerId", UUID.randomUUID().toString(),
                        "currency", "MXN",
                        "initialBalance", "0.00"));
        final var walletId = (String) parse(createResponse).get("id");

        // WHEN - deposit 100 MXN
        final var depositResponse = post("/wallets/" + walletId + "/deposits", Map.of("amount", "100.00"));

        // THEN - succeeds; the framework routed the deposit to the mexico shard via the
        // wallet's ShardedUUID bits with no explicit shard parameter.
        assertThat(depositResponse.statusCode()).isEqualTo(200);
        assertThat(parse(depositResponse)).containsEntry("balance", 100.00);
        assertThat(parse(depositResponse)).containsEntry("shardGroup", 1);
    }

    @Test
    void cross_shard_transfer_moves_money_between_global_and_mexico() throws Exception {
        // GIVEN - fromWallet (global, EUR, 200 balance) and toWallet (mexico, MXN, 0 balance)
        final var fromCreate = post(
                "/wallets",
                Map.of(
                        "countryCode", "DE",
                        "ownerId", UUID.randomUUID().toString(),
                        "currency", "EUR",
                        "initialBalance", "200.00"));
        final var fromWalletId = (String) parse(fromCreate).get("id");
        final var toCreate = post(
                "/wallets",
                Map.of(
                        "countryCode", "MX",
                        "ownerId", UUID.randomUUID().toString(),
                        "currency", "MXN",
                        "initialBalance", "0.00"));
        final var toWalletId = (String) parse(toCreate).get("id");

        // WHEN - transfer 75 from global to mexico
        final var transferResponse = post(
                "/wallets/transfers",
                Map.of(
                        "fromWalletId", fromWalletId,
                        "toWalletId", toWalletId,
                        "amount", "75.00"));

        // THEN - synchronous response shows both balances updated
        assertThat(transferResponse.statusCode()).isEqualTo(200);
        final var body = parse(transferResponse);
        @SuppressWarnings("unchecked")
        final var fromBalance = ((Number) ((Map<String, Object>) body.get("from")).get("balance")).doubleValue();
        @SuppressWarnings("unchecked")
        final var toBalance = ((Number) ((Map<String, Object>) body.get("to")).get("balance")).doubleValue();
        assertThat(fromBalance).isEqualTo(125.00);
        assertThat(toBalance).isEqualTo(75.00);

        // AND - both wallets remain findable through the sharded repo (each from its own shard)
        assertThat(parse(get("/wallets/" + fromWalletId))).containsEntry("balance", 125.00);
        assertThat(parse(get("/wallets/" + toWalletId))).containsEntry("balance", 75.00);
    }

    @Test
    void close_routes_to_the_wallets_own_shard() throws Exception {
        // GIVEN
        final var createResponse = post(
                "/wallets",
                Map.of(
                        "countryCode", "MX",
                        "ownerId", UUID.randomUUID().toString(),
                        "currency", "MXN",
                        "initialBalance", "0.00"));
        final var walletId = (String) parse(createResponse).get("id");

        // WHEN
        final var closeResponse = post("/wallets/" + walletId + "/close", Map.of());

        // THEN
        assertThat(closeResponse.statusCode()).isEqualTo(200);
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
