package io.ekbatan.test.postgres_dual_table_events.wallet.action;

import static io.ekbatan.core.action.ActionExecutor.Builder.actionExecutor;
import static io.ekbatan.core.action.ActionRegistry.Builder.actionRegistry;
import static io.ekbatan.core.repository.RepositoryRegistry.Builder.repositoryRegistry;
import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;
import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.action.persister.event.dual_table.DualTableEventPersister;
import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.test.postgres_dual_table_events.wallet.models.Wallet;
import io.ekbatan.test.postgres_dual_table_events.wallet.repository.WalletRepository;
import java.time.Clock;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
public class WalletActionIntegrationTest {

    @Container
    private static final PostgreSQLContainer db = new PostgreSQLContainer("postgres:latest")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    private static TransactionManager transactionManager;

    @BeforeAll
    static void beforeAll() {
        var dataSourceConfig = DataSourceConfig.Builder.dataSourceConfig()
                .jdbcUrl(db.getJdbcUrl())
                .username(db.getUsername())
                .password(db.getPassword())
                .maximumPoolSize(10)
                .build();
        transactionManager = new TransactionManager(
                ConnectionProvider.hikariConnectionProvider(dataSourceConfig),
                ConnectionProvider.hikariConnectionProvider(dataSourceConfig),
                SQLDialect.POSTGRES);

        Flyway.configure()
                .dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void test_action() throws Exception {
        // GIVEN
        final var objectMapper = new ObjectMapper();

        var databaseRegistry = databaseRegistry()
                .withDatabase(transactionManager.shardIdentifier, transactionManager)
                .defaultShard(transactionManager.shardIdentifier)
                .build();

        final var walletRepository = new WalletRepository(databaseRegistry);

        final var repositoryRegistry = repositoryRegistry()
                .withModelRepository(Wallet.class, walletRepository)
                .build();

        final var clock = Clock.systemUTC();

        final var actionRegistry = actionRegistry()
                .withAction(WalletCreateAction.class, () -> new WalletCreateAction(clock))
                .withAction(WalletDepositMoneyAction.class, () -> new WalletDepositMoneyAction(clock, walletRepository))
                .build();

        final var eventPersister = new DualTableEventPersister(databaseRegistry, objectMapper);

        final var actionExecutor = actionExecutor()
                .databaseRegistry(databaseRegistry)
                .objectMapper(objectMapper)
                .repositoryRegistry(repositoryRegistry)
                .actionRegistry(actionRegistry)
                .eventPersister(eventPersister)
                .build();

        // WHEN
        final var result =
                actionExecutor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params());

        // THEN
        final var fetchedWallet = walletRepository.getById(result.getId().getValue());
        assertThat(result).isEqualTo(fetchedWallet);

        // WHEN
        final var result2 = actionExecutor.execute(
                () -> "test-user", WalletDepositMoneyAction.class, new WalletDepositMoneyAction.Params(result.getId()));

        // THEN
        final var fetchedWallet2 = walletRepository.getById(result2.getId().getId());
        assertThat(result2).isEqualTo(fetchedWallet2);
    }
}
