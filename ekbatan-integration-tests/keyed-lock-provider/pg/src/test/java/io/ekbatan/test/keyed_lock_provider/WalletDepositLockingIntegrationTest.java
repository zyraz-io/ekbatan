package io.ekbatan.test.keyed_lock_provider;

import static io.ekbatan.core.action.ActionExecutor.Builder.actionExecutor;
import static io.ekbatan.core.action.ActionRegistry.Builder.actionRegistry;
import static io.ekbatan.core.concurrent.PostgresKeyedLockProvider.Builder.postgresKeyedLockProvider;
import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;
import static io.ekbatan.core.repository.RepositoryRegistry.Builder.repositoryRegistry;
import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;
import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.concurrent.KeyedLockProvider;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.graalvm.flyway.FlywayHelper;
import io.ekbatan.test.keyed_lock_provider.wallet.action.WalletCreateAction;
import io.ekbatan.test.keyed_lock_provider.wallet.action.WalletDepositAction;
import io.ekbatan.test.keyed_lock_provider.wallet.models.Wallet;
import io.ekbatan.test.keyed_lock_provider.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Demonstrates the README example {@link WalletDepositAction} in action — including its
 * deliberate use of {@link KeyedLockProvider#acquire(Object, Duration)}, which has no wait
 * timeout. The {@link #brokenAcquire_blocksIndefinitely_whenLockHeldElsewhere() broken-behavior
 * test} makes the consequence concrete: if another process holds the wallet's advisory lock,
 * the Action's thread is stuck until that holder releases.
 */
@Testcontainers
public class WalletDepositLockingIntegrationTest {

    @Container
    private static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:latest")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    private static WalletRepository walletRepo;
    private static ActionExecutor executor;
    private static KeyedLockProvider lockProvider;

    @BeforeAll
    static void setUp() {
        var config = dataSourceConfig()
                .jdbcUrl(DB.getJdbcUrl())
                .username(DB.getUsername())
                .password(DB.getPassword())
                .maximumPoolSize(16)
                .build();

        var primary = hikariConnectionProvider(config);
        var secondary = hikariConnectionProvider(config);
        var lockPool = hikariConnectionProvider(config);

        var tm = new TransactionManager(primary, secondary, SQLDialect.POSTGRES);

        FlywayHelper.migrate(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());

        var databases = databaseRegistry().withDatabase(tm).build();

        walletRepo = new WalletRepository(databases);
        lockProvider = postgresKeyedLockProvider().connectionProvider(lockPool).build();

        var clock = Clock.systemUTC();

        var actionRegistry = actionRegistry()
                .withAction(WalletCreateAction.class, new WalletCreateAction(clock))
                .withAction(WalletDepositAction.class, new WalletDepositAction(clock, walletRepo, lockProvider))
                .build();

        executor = actionExecutor()
                .namespace("test.keyed-lock-provider")
                .databaseRegistry(databases)
                .objectMapper(new ObjectMapper())
                .repositoryRegistry(repositoryRegistry()
                        .withModelRepository(Wallet.class, walletRepo)
                        .build())
                .actionRegistry(actionRegistry)
                .build();
    }

    @Test
    void deposit_succeeds_when_lock_is_uncontested() throws Exception {
        // GIVEN — a fresh wallet with 100.00
        var wallet = createWallet(new BigDecimal("100.00"));

        // WHEN — a single uncontested deposit
        var updated = executor.execute(
                () -> "test-user",
                WalletDepositAction.class,
                new WalletDepositAction.Params(wallet.id, new BigDecimal("25.50")));

        // THEN — the new balance is the sum
        assertThat(updated.balance).isEqualByComparingTo(new BigDecimal("125.50"));
        assertThat(walletRepo.getById(wallet.id.getValue()).balance).isEqualByComparingTo(new BigDecimal("125.50"));
    }

    /**
     * Demonstrates the consequence of {@link KeyedLockProvider#acquire(String, Duration)} having
     * no wait timeout — the Duration is {@code maxHold}, not {@code maxWait}. While another
     * holder owns the wallet's advisory lock, the Action's thread is stuck and only proceeds
     * once that holder releases.
     */
    @Test
    void brokenAcquire_blocksIndefinitely_whenLockHeldElsewhere() throws Exception {
        // GIVEN — a wallet, and "another instance" already holding its advisory lock
        var wallet = createWallet(new BigDecimal("100.00"));
        var heldByOther = lockProvider.acquire("wallet:" + wallet.id, Duration.ofMinutes(1));

        // WHEN — an Action attempts to deposit on the same wallet
        var depositFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return executor.execute(
                        () -> "test-user",
                        WalletDepositAction.class,
                        new WalletDepositAction.Params(wallet.id, new BigDecimal("10.00")));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // THEN — the Action is stuck. acquire(...) has no wait timeout, so it blocks until
        // the holder releases. We give it 750ms to fail-fast (which it can't, since there's
        // no fail-fast path), then assert it's still in flight.
        Thread.sleep(750);
        assertThat(depositFuture.isDone())
                .as("Action should still be blocked while the other holder owns the lock")
                .isFalse();

        // WHEN — the "other instance" releases the lock
        heldByOther.close();

        // THEN — the previously-blocked deposit completes successfully and updates the balance
        var updated = depositFuture.get(10, TimeUnit.SECONDS);
        assertThat(updated.balance).isEqualByComparingTo(new BigDecimal("110.00"));
    }

    private static Wallet createWallet(BigDecimal initialBalance) throws Exception {
        return executor.execute(
                () -> "test-user",
                WalletCreateAction.class,
                new WalletCreateAction.Params(UUID.randomUUID(), "EUR", initialBalance));
    }
}
