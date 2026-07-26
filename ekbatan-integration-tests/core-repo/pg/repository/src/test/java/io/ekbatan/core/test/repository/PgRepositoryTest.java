package io.ekbatan.core.test.repository;

import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;
import static io.ekbatan.core.test.model.Dummy.createDummy;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.domain.TypedValue;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.test.model.Dummy;
import io.ekbatan.flyway.FlywayMigrator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.function.Consumer;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
public class PgRepositoryTest extends BaseRepositoryTest {

    @Container
    private static final PostgreSQLContainer DB_CONTAINER = new PostgreSQLContainer("postgres:latest")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    private static final TransactionManager TRANSACTION_MANAGER;
    private static final DummyRepository REPOSITORY;

    static {
        DB_CONTAINER.start();

        final var jdbcUrl = DB_CONTAINER.getJdbcUrl();
        final var username = DB_CONTAINER.getUsername();
        final var password = DB_CONTAINER.getPassword();

        final var dataSourceConfig = DataSourceConfig.Builder.dataSourceConfig()
                .jdbcUrl(jdbcUrl)
                .username(username)
                .password(password)
                .maximumPoolSize(10)
                .build();
        final var primaryConnectionProvider = ConnectionProvider.hikariConnectionProvider(dataSourceConfig);
        final var secondaryConnectionProvider = ConnectionProvider.hikariConnectionProvider(dataSourceConfig);
        TRANSACTION_MANAGER =
                new TransactionManager(primaryConnectionProvider, secondaryConnectionProvider, SQLDialect.POSTGRES);

        FlywayMigrator.migrate(jdbcUrl, username, password);

        var databaseRegistry =
                databaseRegistry().withDatabase(TRANSACTION_MANAGER).build();
        REPOSITORY = new DummyRepository(databaseRegistry);
    }

    public PgRepositoryTest() {
        super(DB_CONTAINER, REPOSITORY);
        this.transactionManager = TRANSACTION_MANAGER;
    }

    /**
     * Postgres-only, because the batch-update builder this guards is Postgres-only - MySQL and
     * MariaDB go through {@code buildUpdateAllQueryMariadb}, which has always bound through the
     * target field.
     *
     * <p>The framework stores timestamps in {@code TIMESTAMP} (no time zone) columns. A batch
     * builder that lets jOOQ infer the bind type from the value's runtime class produces a
     * {@code timestamp with time zone} expression instead, and Postgres then converts it into the
     * column using the <em>session's</em> {@code TimeZone}. Under a UTC session that conversion is
     * a no-op, which is why the rest of the suite cannot see the defect.
     *
     * <p>{@code SET LOCAL} scopes the change to this transaction: it reverts on commit, so the
     * pooled connection goes back to the pool unchanged and no other test is affected. Nothing
     * touches the JVM default zone.
     */
    @Test
    void should_preserve_exact_timestamps_when_the_session_is_not_utc() {
        // GIVEN two dummies sharing a fixed created date - two rows, so updateAll must use the
        // batch builder rather than the single-row short-circuit. Every other column is populated
        // on purpose: this test is about the timestamp, so nothing else may fail the statement
        // first and mask what is being asserted.
        final var createdDate = Instant.parse("2026-01-01T10:00:00Z");
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 2; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, createdDate)
                    .rewardPoints(i)
                    .aliases(List.of("alias-" + i))
                    .build());
        }
        repository.addAll(dummies);

        final var dummiesToUpdate =
                dummies.stream().map(w -> w.withdraw(BigDecimal.ONE)).toList();

        // WHEN the batch update runs on a session whose time zone is not UTC
        transactionManager.inTransaction((Consumer<DSLContext>) ctx -> {
            ctx.execute("SET LOCAL TIME ZONE 'America/New_York'");
            repository.updateAll(dummiesToUpdate);
        });

        // THEN the created date is stored unshifted
        final var fetchedDummies = repository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(TypedValue::getValue).toList());

        assertThat(fetchedDummies).hasSize(2);
        fetchedDummies.forEach(w -> assertThat(w.createdDate).isEqualTo(createdDate));
    }
}
