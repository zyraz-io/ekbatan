package io.ekbatan.test.postgres_simple.wallet.action;

import static io.ekbatan.core.action.ActionExecutor.Builder.actionExecutor;
import static io.ekbatan.core.action.ActionRegistry.Builder.actionRegistry;
import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;
import static io.ekbatan.core.repository.RepositoryRegistry.Builder.repositoryRegistry;
import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;
import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.graalvm.flyway.FlywayHelper;
import io.ekbatan.test.postgres_simple.wallet.models.Wallet;
import io.ekbatan.test.postgres_simple.wallet.models.WalletState;
import io.ekbatan.test.postgres_simple.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
public class SimpleWalletIntegrationTest {

    @Container
    private static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:latest")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    private static WalletRepository walletRepo;
    private static ActionExecutor executor;
    private static DSLContext dsl;

    @BeforeAll
    static void setUp() {
        var config = dataSourceConfig()
                .jdbcUrl(DB.getJdbcUrl())
                .username(DB.getUsername())
                .password(DB.getPassword())
                .maximumPoolSize(10)
                .build();

        var tm = new TransactionManager(
                hikariConnectionProvider(config), hikariConnectionProvider(config), SQLDialect.POSTGRES);

        FlywayHelper.migrate(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());

        var databaseRegistry = databaseRegistry().withDatabase(tm).build();

        walletRepo = new WalletRepository(databaseRegistry);
        dsl = databaseRegistry.primary.get(tm.shardIdentifier);

        var clock = Clock.systemUTC();

        var actionRegistry = actionRegistry()
                .withAction(WalletCreateAction.class, new WalletCreateAction(clock))
                .withAction(WalletDepositAction.class, new WalletDepositAction(clock, walletRepo))
                .withAction(WalletCloseAction.class, new WalletCloseAction(clock, walletRepo))
                .build();

        executor = actionExecutor()
                .namespace("test.simple")
                .databaseRegistry(databaseRegistry)
                .objectMapper(new ObjectMapper())
                .repositoryRegistry(repositoryRegistry()
                        .withModelRepository(Wallet.class, walletRepo)
                        .build())
                .actionRegistry(actionRegistry)
                .build();
    }

    @Test
    void create_action_persists_wallet_and_emits_created_event() throws Exception {
        // WHEN
        var wallet = executor.execute(
                () -> "test-user",
                WalletCreateAction.class,
                new WalletCreateAction.Params(UUID.randomUUID(), "EUR", BigDecimal.TEN));

        // THEN
        var persisted = walletRepo.findById(wallet.id.getValue());
        assertThat(persisted).isPresent();
        assertThat(persisted.get().state).isEqualTo(WalletState.OPENED);
        assertThat(persisted.get().balance).isEqualByComparingTo(BigDecimal.TEN);

        // AND — one created event
        assertThat(eventTypes(wallet.id.getValue())).containsExactly("WalletCreatedEvent");
    }

    @Test
    void deposit_action_updates_balance_and_emits_deposited_event() throws Exception {
        // GIVEN
        var wallet = executor.execute(
                () -> "test-user",
                WalletCreateAction.class,
                new WalletCreateAction.Params(UUID.randomUUID(), "USD", new BigDecimal("100.00")));

        // WHEN
        executor.execute(
                () -> "test-user",
                WalletDepositAction.class,
                new WalletDepositAction.Params(wallet.id, new BigDecimal("25.50")));

        // THEN
        var persisted = walletRepo.getById(wallet.id.getValue());
        assertThat(persisted.balance).isEqualByComparingTo(new BigDecimal("125.50"));
        assertThat(persisted.version).isEqualTo(wallet.version + 1);

        // AND — both events recorded
        assertThat(eventTypes(wallet.id.getValue()))
                .containsExactlyInAnyOrder("WalletCreatedEvent", "WalletMoneyDepositedEvent");
    }

    @Test
    void close_action_transitions_state_and_emits_closed_event() throws Exception {
        // GIVEN
        var wallet = executor.execute(
                () -> "test-user",
                WalletCreateAction.class,
                new WalletCreateAction.Params(UUID.randomUUID(), "EUR", BigDecimal.ONE));

        // WHEN
        executor.execute(() -> "test-user", WalletCloseAction.class, new WalletCloseAction.Params(wallet.id));

        // THEN
        var persisted = walletRepo.getById(wallet.id.getValue());
        assertThat(persisted.state).isEqualTo(WalletState.CLOSED);

        // AND — create + close events
        assertThat(eventTypes(wallet.id.getValue()))
                .containsExactlyInAnyOrder("WalletCreatedEvent", "WalletClosedEvent");
    }

    @Test
    void each_action_writes_one_row_per_event() throws Exception {
        // GIVEN
        var beforeCount = countActionIds();
        var wallet = executor.execute(
                () -> "test-user",
                WalletCreateAction.class,
                new WalletCreateAction.Params(UUID.randomUUID(), "EUR", BigDecimal.ONE));

        executor.execute(
                () -> "test-user",
                WalletDepositAction.class,
                new WalletDepositAction.Params(wallet.id, BigDecimal.TEN));

        // THEN — two distinct action ids added
        assertThat(countActionIds()).isEqualTo(beforeCount + 2);
    }

    private java.util.List<String> eventTypes(UUID walletId) {
        return dsl.select(DSL.field("event_type", String.class))
                .from("eventlog.events")
                .where(DSL.field("model_id").eq(walletId.toString()))
                .fetch(0, String.class);
    }

    private long countActionIds() {
        return dsl.select(DSL.countDistinct(DSL.field("action_id")))
                .from("eventlog.events")
                .fetchOne(0, long.class);
    }
}
