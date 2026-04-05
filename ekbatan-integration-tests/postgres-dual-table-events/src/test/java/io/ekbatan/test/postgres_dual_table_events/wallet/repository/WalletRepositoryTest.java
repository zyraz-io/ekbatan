package io.ekbatan.test.postgres_dual_table_events.wallet.repository;

import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;
import static io.ekbatan.test.postgres_dual_table_events.generated.jooq.public_schema.tables.Wallets.WALLETS;
import static io.ekbatan.test.postgres_dual_table_events.wallet.models.Wallet.createWallet;
import static java.lang.String.format;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.domain.TypedValue;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.repository.exception.EntityNotFoundException;
import io.ekbatan.core.repository.exception.StaleRecordException;
import io.ekbatan.test.postgres_dual_table_events.wallet.models.Wallet;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class WalletRepositoryTest {

    @Container
    private static final PostgreSQLContainer db = new PostgreSQLContainer("postgres:latest")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    private static TransactionManager transactionManager;
    private static WalletRepository walletRepository;

    @BeforeAll
    static void setUp() {
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

        var databaseRegistry = databaseRegistry()
                .withDatabase(transactionManager.shardIdentifier, transactionManager)
                .defaultShard(transactionManager.shardIdentifier)
                .build();
        walletRepository = new WalletRepository(databaseRegistry);
    }

    @Test
    void should_add_correctly() {
        // GIVEN
        final var wallet = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();

        // WHEN
        final var added = walletRepository.add(wallet);
        final var found = walletRepository.findById(wallet.getId().getValue());

        // THEN
        assertThat(added).isNotNull();
        assertThat(added.id).isEqualTo(wallet.id);
        assertThat(added.state).isEqualTo(wallet.state);
        assertThat(added.ownerId).isEqualTo(wallet.ownerId);
        assertThat(added.currency.getCurrencyCode()).isEqualTo("EUR");
        assertThat(added.balance).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(added.createdDate).isEqualTo(wallet.createdDate);
        assertThat(added.updatedDate).isEqualTo(wallet.updatedDate);
        assertThat(added.version).isEqualTo(1L);

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().id).isEqualTo(wallet.id);
        assertThat(found.orElseThrow().state).isEqualTo(wallet.state);
        assertThat(found.orElseThrow().ownerId).isEqualTo(wallet.ownerId);
        assertThat(found.orElseThrow().currency.getCurrencyCode()).isEqualTo("EUR");
        assertThat(found.orElseThrow().balance).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(found.orElseThrow().createdDate).isEqualTo(wallet.createdDate);
        assertThat(found.orElseThrow().updatedDate).isEqualTo(wallet.updatedDate);
        assertThat(found.orElseThrow().version).isEqualTo(1L);
    }

    @Test
    void should_add_correctly_in_transaction() {
        // GIVEN / WHEN

        final var wallet = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();

        transactionManager.inTransaction(_ -> {
            walletRepository.add(wallet);
        });

        // THEN

        final var fetchedWallet = walletRepository.findById(wallet.getId().getValue());
        assertThat(fetchedWallet).isPresent();
        assertThat(fetchedWallet.orElseThrow().id).isEqualTo(wallet.id);
        assertThat(fetchedWallet.orElseThrow().state).isEqualTo(wallet.state);
        assertThat(fetchedWallet.orElseThrow().ownerId).isEqualTo(wallet.ownerId);
        assertThat(fetchedWallet.orElseThrow().currency.getCurrencyCode()).isEqualTo("EUR");
        assertThat(fetchedWallet.orElseThrow().balance.intValue()).isEqualTo(10);
        assertThat(fetchedWallet.orElseThrow().createdDate).isEqualTo(wallet.createdDate);
        assertThat(fetchedWallet.orElseThrow().updatedDate).isEqualTo(wallet.updatedDate);
    }

    @Test
    void should_rollback_add_in_transaction_upon_exception() {
        // GIVEN / WHEN

        final var wallet = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();

        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                walletRepository.add(wallet);

                throw new RuntimeException();
            });
        } catch (Exception _) {

        }

        // THEN

        final var fetchedWallet = walletRepository.findById(wallet.getId().getValue());
        assertThat(fetchedWallet).isEmpty();
    }

    @Test
    void should_addNoResult() {
        // GIVEN
        final var wallet = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();

        // WHEN
        walletRepository.addNoResult(wallet);

        // THEN
        final var fetchedWallet = walletRepository.findById(wallet.getId().getValue());
        assertThat(fetchedWallet).isPresent();
        assertThat(fetchedWallet.orElseThrow().id).isEqualTo(wallet.id);
        assertThat(fetchedWallet.orElseThrow().state).isEqualTo(wallet.state);
        assertThat(fetchedWallet.orElseThrow().ownerId).isEqualTo(wallet.ownerId);
        assertThat(fetchedWallet.orElseThrow().currency.getCurrencyCode()).isEqualTo("EUR");
        assertThat(fetchedWallet.orElseThrow().balance).isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    void should_addNoResult_inTransaction() {
        // GIVEN
        final var wallet = createWallet(
                        randomUUID(), Currency.getInstance("USD"), BigDecimal.valueOf(100), Instant.now())
                .build();

        // WHEN
        transactionManager.inTransaction(_ -> {
            walletRepository.addNoResult(wallet);
        });

        // THEN
        final var fetchedWallet = walletRepository.findById(wallet.getId().getValue());
        assertThat(fetchedWallet).isPresent();
        assertThat(fetchedWallet.orElseThrow().id).isEqualTo(wallet.id);
        assertThat(fetchedWallet.orElseThrow().state).isEqualTo(wallet.state);
        assertThat(fetchedWallet.orElseThrow().ownerId).isEqualTo(wallet.ownerId);
        assertThat(fetchedWallet.orElseThrow().currency.getCurrencyCode()).isEqualTo("USD");
        assertThat(fetchedWallet.orElseThrow().balance).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    void should_addAll() {

        // GIVEN
        final var wallets = new ArrayList<Wallet>();
        for (int i = 0; i < 10; i++) {
            wallets.add(createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                    .build());
        }

        // WHEN
        final var addedWallets = walletRepository.addAll(wallets);

        // THEN
        final var fetchWallets = walletRepository.findAllByIds(
                wallets.stream().map(Wallet::getId).map(TypedValue::getValue).toList());

        assertThat(fetchWallets).hasSize(10);
        assertThat(fetchWallets).hasSameElementsAs(addedWallets);
    }

    @Test
    void should_addAll_inTransaction() {

        // GIVEN
        final var wallets = new ArrayList<Wallet>();
        for (int i = 0; i < 10; i++) {
            wallets.add(createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                    .build());
        }

        // WHEN
        transactionManager.inTransaction(_ -> {
            walletRepository.addAll(wallets);
        });

        // THEN
        final var fetchWallets = walletRepository.findAllByIds(
                wallets.stream().map(Wallet::getId).map(TypedValue::getValue).toList());

        assertThat(fetchWallets).hasSize(10);
    }

    @Test
    void should_rollback_addAll_inTransaction_when_exception() {

        // GIVEN
        final var wallets = new ArrayList<Wallet>();
        for (int i = 0; i < 10; i++) {
            wallets.add(createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                    .build());
        }

        // WHEN
        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                walletRepository.addAll(wallets);
                throw new RuntimeException();
            });
        } catch (Exception _) {
        }

        // THEN
        final var fetchWallets = walletRepository.findAllByIds(
                wallets.stream().map(Wallet::getId).map(TypedValue::getValue).toList());

        assertThat(fetchWallets).hasSize(0);
    }

    @Test
    void should_addAllNoResult() {
        // GIVEN
        final var wallets = new ArrayList<Wallet>();
        for (int i = 0; i < 10; i++) {
            wallets.add(createWallet(
                            randomUUID(),
                            Currency.getInstance(i % 2 == 0 ? "EUR" : "USD"),
                            BigDecimal.TEN.add(BigDecimal.valueOf(i)),
                            Instant.now())
                    .build());
        }

        // WHEN
        walletRepository.addAllNoResult(wallets);

        // THEN
        final var fetchWallets = walletRepository.findAllByIds(
                wallets.stream().map(Wallet::getId).map(TypedValue::getValue).toList());

        assertThat(fetchWallets).hasSize(10);
        assertThat(fetchWallets).allSatisfy(w -> {
            assertThat(w).isNotNull();
            assertThat(w.currency.getCurrencyCode()).isIn("EUR", "USD");
            assertThat(w.balance).isGreaterThanOrEqualTo(BigDecimal.TEN);
        });
    }

    @Test
    void should_addAllNoResult_inTransaction() {
        // GIVEN
        final var wallets = new ArrayList<Wallet>();
        for (int i = 0; i < 5; i++) {
            wallets.add(createWallet(
                            randomUUID(),
                            Currency.getInstance(i % 2 == 0 ? "GBP" : "CHF"),
                            BigDecimal.valueOf(100 + i),
                            Instant.now())
                    .build());
        }

        // WHEN
        transactionManager.inTransaction(_ -> {
            walletRepository.addAllNoResult(wallets);
        });

        // THEN
        final var fetchWallets = walletRepository.findAllByIds(
                wallets.stream().map(Wallet::getId).map(TypedValue::getValue).toList());

        assertThat(fetchWallets).hasSize(5);
        assertThat(fetchWallets).allSatisfy(w -> {
            assertThat(w).isNotNull();
            assertThat(w.currency.getCurrencyCode()).isIn("GBP", "CHF");
            assertThat(w.balance).isGreaterThanOrEqualTo(BigDecimal.valueOf(100));
        });
    }

    @Test
    void should_rollback_addAllNoResult_inTransaction_when_exception() {
        // GIVEN
        final var wallets = new ArrayList<Wallet>();
        for (int i = 0; i < 5; i++) {
            wallets.add(
                    createWallet(randomUUID(), Currency.getInstance("JPY"), BigDecimal.valueOf(1000 + i), Instant.now())
                            .build());
        }

        // WHEN
        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                walletRepository.addAllNoResult(wallets);
                throw new RuntimeException("Simulated transaction failure");
            });
        } catch (Exception _) {
        }

        // THEN
        final var fetchWallets = walletRepository.findAllByIds(
                wallets.stream().map(Wallet::getId).map(TypedValue::getValue).toList());

        assertThat(fetchWallets).isEmpty();
    }

    @Test
    void should_update() {
        // GIVEN
        final var wallet = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        walletRepository.add(wallet);
        final var walletToUpdate = wallet.withdraw(BigDecimal.TWO);

        // WHEN
        final var updatedWallet = walletRepository.update(walletToUpdate);

        // THEN
        final var fetchedWallet = walletRepository.getById(wallet.getId().getValue());

        assertThat(fetchedWallet).isNotNull();
        assertThat(fetchedWallet.balance).isEqualByComparingTo(BigDecimal.valueOf(8));
        assertThat(fetchedWallet.version).isEqualTo(2);

        assertThat(updatedWallet).isNotNull();
        assertThat(updatedWallet.balance).isEqualByComparingTo(BigDecimal.valueOf(8));
        assertThat(updatedWallet.version).isEqualTo(2);
    }

    @Test
    void should_update_inTransaction() {
        // GIVEN
        final var wallet = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        walletRepository.add(wallet);
        final var walletToUpdate = wallet.withdraw(BigDecimal.TWO);

        // WHEN
        transactionManager.inTransaction(_ -> {
            walletRepository.update(walletToUpdate);
        });

        // THEN
        final var fetchedWallet = walletRepository.getById(wallet.getId().getValue());
        assertThat(fetchedWallet).isNotNull();
        assertThat(fetchedWallet.balance).isEqualByComparingTo(BigDecimal.valueOf(8));
        assertThat(fetchedWallet.version).isEqualTo(2);
    }

    @Test
    void should_not_update_inTransaction_when_exception() {
        // GIVEN
        final var wallet = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        walletRepository.add(wallet);
        final var walletToUpdate = wallet.withdraw(BigDecimal.TWO);

        // WHEN
        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                walletRepository.update(walletToUpdate);

                throw new RuntimeException();
            });
        } catch (Exception _) {
        }

        // THEN
        final var fetchedWallet = walletRepository.getById(wallet.getId().getValue());
        assertThat(fetchedWallet).isNotNull();
        assertThat(fetchedWallet.balance).isEqualByComparingTo(wallet.balance);
        assertThat(fetchedWallet.version).isEqualTo(1);
    }

    @Test
    void should_updateNoResult() {
        // GIVEN
        final var wallet = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        walletRepository.add(wallet);
        final var walletToUpdate = wallet.withdraw(BigDecimal.TWO);

        // WHEN
        walletRepository.updateNoResult(walletToUpdate);

        // THEN
        final var fetchedWallet = walletRepository.getById(wallet.getId().getValue());
        assertThat(fetchedWallet).isNotNull();
        assertThat(fetchedWallet.balance).isEqualByComparingTo(BigDecimal.valueOf(8));
        assertThat(fetchedWallet.version).isEqualTo(2);
    }

    @Test
    void should_updateNoResult_inTransaction() {
        // GIVEN
        final var wallet = createWallet(
                        randomUUID(), Currency.getInstance("USD"), new BigDecimal("100.50"), Instant.now())
                .build();
        walletRepository.add(wallet);
        final var walletToUpdate = wallet.withdraw(new BigDecimal("20.25"));

        // WHEN
        transactionManager.inTransaction(_ -> {
            walletRepository.updateNoResult(walletToUpdate);
        });

        // THEN
        final var fetchedWallet = walletRepository.getById(wallet.getId().getValue());
        assertThat(fetchedWallet).isNotNull();
        assertThat(fetchedWallet.balance).isEqualByComparingTo(new BigDecimal("80.25"));
        assertThat(fetchedWallet.version).isEqualTo(2);
    }

    @Test
    void should_rollback_updateNoResult_inTransaction_when_exception() {
        // GIVEN
        final var wallet = createWallet(
                        randomUUID(), Currency.getInstance("GBP"), new BigDecimal("50.00"), Instant.now())
                .build();
        walletRepository.add(wallet);
        final var walletToUpdate = wallet.withdraw(BigDecimal.TEN);

        // WHEN
        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                walletRepository.updateNoResult(walletToUpdate);
                throw new RuntimeException("Simulated transaction failure");
            });
        } catch (Exception _) {
        }

        // THEN - Verify the update was rolled back
        final var fetchedWallet = walletRepository.getById(wallet.getId().getValue());
        assertThat(fetchedWallet).isNotNull();
        assertThat(fetchedWallet.balance).isEqualByComparingTo(wallet.balance);
        assertThat(fetchedWallet.version).isEqualTo(1);
    }

    @Test
    void should_throw_StaleRecordException_when_updating_stale_version() {
        // GIVEN
        final var wallet = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        walletRepository.add(wallet);

        final var walletToUpdate = wallet.withdraw(BigDecimal.ONE);
        final var walletToUpdateStale = wallet.withdraw(BigDecimal.TWO);
        walletRepository.updateNoResult(walletToUpdate);

        // WHEN & THEN
        assertThatThrownBy(() -> walletRepository.updateNoResult(walletToUpdateStale))
                .isInstanceOf(StaleRecordException.class)
                .hasMessageContaining("was concurrently modified or not found");

        final var fetchedWallet = walletRepository.getById(wallet.getId().getValue());
        assertThat(fetchedWallet).isNotNull();
        assertThat(fetchedWallet.balance).isEqualByComparingTo(walletToUpdate.balance);
        assertThat(fetchedWallet.version).isEqualTo(2);
    }

    @Test
    void should_updateAll() {
        // GIVEN
        final var wallets = new ArrayList<Wallet>();
        for (int i = 0; i < 5; i++) {
            wallets.add(createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                    .build());
        }
        walletRepository.addAll(wallets);

        final var walletsToUpdate =
                wallets.stream().map(w -> w.withdraw(BigDecimal.ONE)).toList();

        // WHEN
        final var updatedWallets = walletRepository.updateAll(walletsToUpdate);

        // THEN
        final var fetchedWallets = walletRepository.findAllByIds(
                wallets.stream().map(Wallet::getId).map(TypedValue::getValue).toList());
        assertThat(fetchedWallets).hasSize(5);
        assertThat(fetchedWallets).hasSameElementsAs(updatedWallets);

        assertThat(updatedWallets).hasSize(5);
        updatedWallets.forEach(w -> {
            assertThat(w.balance).isEqualByComparingTo(BigDecimal.valueOf(9));
            assertThat(w.version).isEqualTo(2);
        });
    }

    @Test
    void should_updateAll_inTransaction() {
        // GIVEN
        final var wallets = new ArrayList<Wallet>();
        for (int i = 0; i < 5; i++) {
            wallets.add(createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                    .build());
        }
        walletRepository.addAll(wallets);

        final var walletsToUpdate =
                wallets.stream().map(w -> w.withdraw(BigDecimal.ONE)).toList();

        // WHEN
        transactionManager.inTransaction(_ -> {
            walletRepository.updateAll(walletsToUpdate);
        });

        // THEN
        final var fetchedWallets = walletRepository.findAllByIds(
                wallets.stream().map(Wallet::getId).map(TypedValue::getValue).toList());

        assertThat(fetchedWallets).hasSize(5);
        fetchedWallets.forEach(w -> {
            assertThat(w.balance).isEqualByComparingTo(BigDecimal.valueOf(9));
            assertThat(w.version).isEqualTo(2);
        });
    }

    @Test
    void should_throw_optimistic_lock_exception_when_updateAll_with_stale_versions() {
        // GIVEN
        final var wallet1 = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var wallet2 = createWallet(randomUUID(), Currency.getInstance("USD"), BigDecimal.TEN, Instant.now())
                .build();
        walletRepository.addAll(List.of(wallet1, wallet2));

        final var wallet1Changed = wallet1.withdraw(BigDecimal.ONE);
        walletRepository.update(wallet1Changed);

        final var wallet1StaleChanged =
                wallet1.withdraw(BigDecimal.ONE); // This will have version 0 but current version is 1
        final var wallet2Changed = wallet2.withdraw(BigDecimal.ONE);

        // WHEN / THEN
        assertThatThrownBy(() -> walletRepository.updateAll(List.of(wallet2Changed, wallet1StaleChanged)))
                .isInstanceOf(StaleRecordException.class)
                .hasMessageContaining("Expected to update 2 records but only updated 1");

        final var fetchedWallets = walletRepository.findAllByIds(
                List.of(wallet1.getId().getValue(), wallet2.getId().getValue()));

        assertThat(fetchedWallets).hasSize(2);

        final var wallet1Fetched =
                fetchedWallets.stream().filter(w -> w.id.equals(wallet1.id)).findFirst();
        assertThat(wallet1Fetched).isPresent();
        assertThat(wallet1Fetched.orElseThrow().balance).isEqualByComparingTo(BigDecimal.valueOf(9));
        assertThat(wallet1Fetched.orElseThrow().version).isEqualTo(2);

        final var wallet2Fetched =
                fetchedWallets.stream().filter(w -> w.id.equals(wallet2.id)).findFirst();
        assertThat(wallet2Fetched).isPresent();
        assertThat(wallet2Fetched.orElseThrow().balance).isEqualByComparingTo(BigDecimal.valueOf(9));
        assertThat(wallet2Fetched.orElseThrow().version).isEqualTo(2);
    }

    @Test
    void should_throw_StaleRecordException_when_updating_stale_version_with_update_method() {
        // GIVEN
        final var wallet = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        walletRepository.add(wallet);

        final var walletToUpdate = wallet.withdraw(BigDecimal.ONE);
        final var walletToUpdateStale = wallet.withdraw(BigDecimal.TWO);
        walletRepository.update(walletToUpdate);

        // WHEN & THEN
        assertThatThrownBy(() -> walletRepository.update(walletToUpdateStale))
                .isInstanceOf(StaleRecordException.class)
                .hasMessageContaining("was concurrently modified or not found");

        final var fetchedWallet = walletRepository.getById(wallet.getId().getValue());
        assertThat(fetchedWallet).isNotNull();
        assertThat(fetchedWallet.balance).isEqualByComparingTo(walletToUpdate.balance);
        assertThat(fetchedWallet.version).isEqualTo(2);
    }

    @Test
    void should_rollback_updateAll_inTransaction_when_exception() {
        // GIVEN
        final var wallets = new ArrayList<Wallet>();
        for (int i = 0; i < 5; i++) {
            wallets.add(createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                    .build());
        }
        walletRepository.addAll(wallets);

        final var originalBalances = wallets.stream().collect(Collectors.toMap(w -> w.id, w -> w.balance));

        final var walletsToUpdate =
                wallets.stream().map(w -> w.withdraw(BigDecimal.ONE)).toList();

        // WHEN
        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                walletRepository.updateAll(walletsToUpdate);
                throw new RuntimeException("Test exception");
            });
        } catch (Exception _) {
        }

        // THEN
        final var fetchedWallets = walletRepository.findAllByIds(
                wallets.stream().map(Wallet::getId).map(TypedValue::getValue).toList());

        assertThat(fetchedWallets).hasSize(5);
        fetchedWallets.forEach(w -> {
            assertThat(w.balance).isEqualByComparingTo(originalBalances.get(w.id));
            assertThat(w.version).isOne();
        });
    }

    @Test
    void should_updateAllNoResult() {
        // GIVEN
        final var wallets = new ArrayList<Wallet>();
        for (int i = 0; i < 5; i++) {
            wallets.add(createWallet(
                            randomUUID(),
                            Currency.getInstance(i % 2 == 0 ? "EUR" : "USD"),
                            BigDecimal.TEN.add(BigDecimal.valueOf(i)),
                            Instant.now())
                    .build());
        }
        walletRepository.addAll(wallets);

        final var walletsToUpdate =
                wallets.stream().map(w -> w.withdraw(BigDecimal.ONE)).toList();

        // WHEN
        walletRepository.updateAllNoResult(walletsToUpdate);

        // THEN
        final var fetchedWallets = walletRepository.findAllByIds(
                wallets.stream().map(Wallet::getId).map(TypedValue::getValue).toList());

        assertThat(fetchedWallets).hasSize(5);
        fetchedWallets.forEach(w -> {
            assertThat(w).isNotNull();
            assertThat(w.currency.getCurrencyCode()).isIn("EUR", "USD");
            assertThat(w.balance).isGreaterThanOrEqualTo(BigDecimal.valueOf(9)); // 10 - 1, 11 - 1, etc.
            assertThat(w.version).isEqualTo(2);
        });
    }

    @Test
    void should_updateAllNoResult_inTransaction() {
        // GIVEN
        final var wallets = new ArrayList<Wallet>();
        for (int i = 0; i < 3; i++) {
            wallets.add(createWallet(
                            randomUUID(),
                            Currency.getInstance("GBP"),
                            new BigDecimal("100.00").add(BigDecimal.valueOf(i * 10)),
                            Instant.now())
                    .build());
        }
        walletRepository.addAll(wallets);

        final var walletsToUpdate =
                wallets.stream().map(w -> w.withdraw(new BigDecimal("5.50"))).toList();

        // WHEN
        transactionManager.inTransaction(_ -> {
            walletRepository.updateAllNoResult(walletsToUpdate);
        });

        // THEN
        final var fetchedWallets = walletRepository.findAllByIds(
                wallets.stream().map(Wallet::getId).map(TypedValue::getValue).toList());

        assertThat(fetchedWallets).hasSize(3);
        fetchedWallets.forEach(w -> {
            assertThat(w).isNotNull();
            assertThat(w.currency.getCurrencyCode()).isEqualTo("GBP");
            assertThat(w.version).isEqualTo(2);
        });

        final var fetchedMap = fetchedWallets.stream().collect(Collectors.toMap(d -> d.id, d -> d));

        assertThat(fetchedMap.get(wallets.get(0).id).balance)
                .isEqualByComparingTo(new BigDecimal("94.50")); // 100.00 - 5.50
        assertThat(fetchedMap.get(wallets.get(1).id).balance).isEqualByComparingTo(new BigDecimal("104.50"));
        assertThat(fetchedMap.get(wallets.get(2).id).balance).isEqualByComparingTo(new BigDecimal("114.50"));
    }

    @Test
    void should_rollback_updateAllNoResult_inTransaction_when_exception() {
        // GIVEN
        final var wallets = new ArrayList<Wallet>();
        for (int i = 0; i < 4; i++) {
            wallets.add(createWallet(
                            randomUUID(),
                            Currency.getInstance("JPY"),
                            new BigDecimal("1000").add(BigDecimal.valueOf(i * 100)),
                            Instant.now())
                    .build());
        }
        walletRepository.addAll(wallets);

        final var walletsToUpdate =
                wallets.stream().map(w -> w.withdraw(new BigDecimal("100"))).toList();

        // WHEN
        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                walletRepository.updateAllNoResult(walletsToUpdate);
                throw new RuntimeException("Simulated transaction failure");
            });
        } catch (Exception _) {
        }

        // THEN
        final var fetchedWallets = walletRepository.findAllByIds(
                wallets.stream().map(Wallet::getId).map(TypedValue::getValue).toList());

        assertThat(fetchedWallets).hasSize(4);
        fetchedWallets.forEach(w -> {
            assertThat(w.version).isEqualTo(1);
            assertThat(w.balance).isGreaterThanOrEqualTo(new BigDecimal("1000"));
        });
    }

    @Test
    void should_throw_StaleRecordException_when_updatingAll_with_stale_versions() {
        // GIVEN
        final var wallets = new ArrayList<Wallet>();
        for (int i = 0; i < 3; i++) {
            wallets.add(createWallet(randomUUID(), Currency.getInstance("EUR"), new BigDecimal("50.00"), Instant.now())
                    .build());
        }
        walletRepository.addAll(wallets);

        final var walletsToUpdate =
                wallets.stream().map(w -> w.withdraw(BigDecimal.TEN)).toList();

        final var firstWalletToUpdate = walletsToUpdate.getFirst();
        walletRepository.updateNoResult(firstWalletToUpdate);

        // WHEN & THEN
        assertThatThrownBy(() -> walletRepository.updateAllNoResult(walletsToUpdate))
                .isInstanceOf(StaleRecordException.class);
    }

    @Test
    void should_findById() {
        // GIVEN
        var wallet = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        wallet = walletRepository.add(wallet);

        // WHEN
        final var found = walletRepository.findById(wallet.getId().getValue());

        // THEN
        assertThat(found).isPresent();
        assertThat(found.orElseThrow()).isEqualTo(wallet);
    }

    @Test
    void should_return_empty_when_findById_with_non_existent_id() {
        // WHEN
        final var found = walletRepository.findById(randomUUID());

        // THEN
        assertThat(found).isEmpty();
    }

    @Test
    void should_return_empty_when_findById_when_marked_as_deleted() {
        // GIVEN
        var wallet = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        walletRepository.add(wallet);

        final var deletedWallet = wallet.delete();
        walletRepository.update(deletedWallet);

        // WHEN
        final var found = walletRepository.findById(wallet.getId().getValue());

        // THEN
        assertThat(found).isEmpty();
    }

    @Test
    void should_getById() {
        // GIVEN
        var wallet = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        wallet = walletRepository.add(wallet);

        // WHEN
        final var found = walletRepository.getById(wallet.getId().getValue());

        // THEN
        assertThat(found).isEqualTo(wallet);
    }

    @Test
    void should_throw_EntityNotFoundException_when_getById_with_non_existent_id() {
        // GIVEN
        final var id = randomUUID();

        // WHEN / THEN
        assertThatThrownBy(() -> walletRepository.getById(id))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(format("No entity found with id: %s[id=%s]", Wallet.class.getSimpleName(), id));
    }

    @Test
    void should_throw_EntityNotFoundException_when_getById_when_marked_as_deleted() {
        // GIVEN
        var wallet = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        walletRepository.add(wallet);

        final var deletedWallet = wallet.delete();
        walletRepository.update(deletedWallet);

        // WHEN
        final var found = walletRepository.findById(wallet.getId().getValue());

        // THEN
        assertThat(found).isEmpty();
    }

    @Test
    void should_findAllByIds() {
        // GIVEN
        final var wallet1 = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var wallet2 = createWallet(randomUUID(), Currency.getInstance("USD"), BigDecimal.ONE, Instant.now())
                .build();
        final var wallet3 = createWallet(randomUUID(), Currency.getInstance("GBP"), BigDecimal.ZERO, Instant.now())
                .build();

        walletRepository.addAll(List.of(wallet1, wallet2, wallet3));

        // WHEN
        final var foundWallets = walletRepository.findAllByIds(
                List.of(wallet1.getId().getValue(), wallet3.getId().getValue(), randomUUID()));

        // THEN
        assertThat(foundWallets).hasSize(2);
        assertThat(foundWallets).extracting(w -> w.id).containsExactlyInAnyOrder(wallet1.id, wallet3.id);
    }

    @Test
    void findAllByIds_should_return_non_DELETED_items() {
        // GIVEN
        final var wallet1 = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var wallet2 = createWallet(randomUUID(), Currency.getInstance("USD"), BigDecimal.ONE, Instant.now())
                .build();

        walletRepository.addAll(List.of(wallet1, wallet2));

        final var deletedWallet2 = wallet2.delete();
        walletRepository.update(deletedWallet2);

        // WHEN
        final var foundWallets = walletRepository.findAllByIds(
                List.of(wallet1.getId().getValue(), wallet2.getId().getValue()));

        // THEN
        assertThat(foundWallets).hasSize(1);
        assertThat(foundWallets).extracting(w -> w.id).containsExactly(wallet1.id);
    }

    @Test
    void should_return_empty_list_when_findAllByIds_with_empty_input() {
        // WHEN
        final var foundWallets = walletRepository.findAllByIds(List.of());

        // THEN
        assertThat(foundWallets).isEmpty();
    }

    @Test
    void should_findAll() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(WALLETS).cascade().execute();

        final var wallet1 = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var wallet2 = createWallet(randomUUID(), Currency.getInstance("USD"), BigDecimal.ONE, Instant.now())
                .build();
        final var wallet3 = createWallet(randomUUID(), Currency.getInstance("GBP"), BigDecimal.ZERO, Instant.now())
                .build();
        final var wallet4 = createWallet(randomUUID(), Currency.getInstance("GBP"), BigDecimal.ZERO, Instant.now())
                .build();

        walletRepository.addAll(List.of(wallet1, wallet2, wallet3, wallet4));

        final var deletedWallet3 = wallet3.delete();
        walletRepository.update(deletedWallet3);

        // WHEN
        final var result = walletRepository.findAll();

        // THEN
        assertThat(result).hasSize(3);
        assertThat(result).extracting(w -> w.id).containsExactlyInAnyOrder(wallet1.id, wallet2.id, wallet4.id);
    }

    @Test
    void should_findAll_excludes_deleted() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(WALLETS).cascade().execute();

        final var wallet1 = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var wallet2 = createWallet(randomUUID(), Currency.getInstance("USD"), BigDecimal.ONE, Instant.now())
                .build();

        walletRepository.addAll(List.of(wallet1, wallet2));

        final var deletedWallet1 = wallet1.delete();
        walletRepository.update(deletedWallet1);

        // WHEN
        final var result = walletRepository.findAll();

        // THEN
        assertThat(result).hasSize(1);
        assertThat(result).extracting(w -> w.id).containsExactly(wallet2.id);
    }

    @Test
    void should_existsById() {
        // GIVEN
        var wallet = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        wallet = walletRepository.add(wallet);

        // WHEN
        final var exists = walletRepository.existsById(wallet.getId().getValue());
        final var notExists = walletRepository.existsById(randomUUID());

        // THEN
        assertThat(exists).isTrue();
        assertThat(notExists).isFalse();
    }

    @Test
    void should_existsById_excludes_deleted() {
        // GIVEN
        var wallet = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        walletRepository.add(wallet);

        final var deletedWallet = wallet.delete();
        walletRepository.update(deletedWallet);

        // WHEN
        final var exists = walletRepository.existsById(wallet.getId().getValue());

        // THEN
        assertThat(exists).isFalse();
    }

    @Test
    void should_count_excludes_deleted() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(WALLETS).cascade().execute();

        final var wallet1 = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var wallet2 = createWallet(randomUUID(), Currency.getInstance("USD"), BigDecimal.TEN, Instant.now())
                .build();
        final var wallet3 = createWallet(randomUUID(), Currency.getInstance("GBP"), BigDecimal.TEN, Instant.now())
                .build();

        walletRepository.addAll(List.of(wallet1, wallet2, wallet3));

        walletRepository.update(wallet2.delete());

        // WHEN
        final var count = walletRepository.count();

        // THEN
        assertThat(count).isEqualTo(2);
    }

    @Test
    void should_countWhere_excludes_deleted() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(WALLETS).cascade().execute();

        final var wallet1 = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var wallet2 = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var wallet3 = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var wallet4 = createWallet(randomUUID(), Currency.getInstance("USD"), BigDecimal.TEN, Instant.now())
                .build();

        walletRepository.addAll(List.of(wallet1, wallet2, wallet3, wallet4));

        walletRepository.update(wallet3.delete());

        // WHEN
        final var count = walletRepository.countWhere(WALLETS.CURRENCY.eq("EUR"));

        // THEN
        assertThat(count).isEqualTo(2);
    }

    @Test
    void should_findOneWhere_excludes_deleted() {
        // GIVEN
        final var wallet1 = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var wallet2 = createWallet(randomUUID(), Currency.getInstance("USD"), BigDecimal.TEN, Instant.now())
                .build();

        walletRepository.addAll(List.of(wallet1, wallet2));

        walletRepository.update(wallet1.delete());

        // WHEN
        final var foundDeleted =
                walletRepository.findOneWhere(WALLETS.ID.eq(wallet1.getId().getValue()));
        final var foundActive =
                walletRepository.findOneWhere(WALLETS.ID.eq(wallet2.getId().getValue()));

        // THEN
        assertThat(foundDeleted).isEmpty();
        assertThat(foundActive).isPresent();
        assertThat(foundActive.get().id).isEqualTo(wallet2.id);
    }

    @Test
    void should_findAllWhere_condition_excludes_deleted() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(WALLETS).cascade().execute();

        final var wallet1 = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var wallet2 = createWallet(
                        randomUUID(), Currency.getInstance("EUR"), BigDecimal.valueOf(20), Instant.now())
                .build();
        final var wallet3 = createWallet(
                        randomUUID(), Currency.getInstance("USD"), BigDecimal.valueOf(30), Instant.now())
                .build();

        walletRepository.addAll(List.of(wallet1, wallet2, wallet3));

        walletRepository.update(wallet1.delete());

        // WHEN
        final var result = walletRepository.findAllWhere(WALLETS.CURRENCY.eq("EUR"));

        // THEN
        assertThat(result).hasSize(1);
        assertThat(result).extracting(w -> w.id).containsExactly(wallet2.id);
    }

    @Test
    void should_existsWhere_returns_true_when_exists() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(WALLETS).cascade().execute();

        final var wallet = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        walletRepository.add(wallet);

        // WHEN
        final var exists = walletRepository.existsWhere(WALLETS.CURRENCY.eq("EUR"));

        // THEN
        assertThat(exists).isTrue();
    }

    @Test
    void should_existsWhere_returns_false_when_deleted() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(WALLETS).cascade().execute();

        final var wallet = createWallet(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        walletRepository.add(wallet);

        walletRepository.update(wallet.delete());

        // WHEN
        final var exists = walletRepository.existsWhere(WALLETS.CURRENCY.eq("EUR"));

        // THEN
        assertThat(exists).isFalse();
    }
}
