package io.ekbatan.core.test.repository;

import static java.util.Optional.empty;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
public class PgBaseRepositoryTest extends BaseRepositoryTest {

    @Container
    private static final PostgreSQLContainer DB_CONTAINER = new PostgreSQLContainer("postgres:latest")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    private static final TransactionManager TRANSACTION_MANAGER;
    private static final DummyRepository REPOSITORY;

    static {
        DB_CONTAINER.start();

        final var jdbcUrl = DB_CONTAINER.getJdbcUrl();
        final var username = DB_CONTAINER.getUsername();
        final var password = DB_CONTAINER.getPassword();

        final var dataSourceConfig =
                new DataSourceConfig(jdbcUrl, username, password, empty(), 10, empty(), empty(), empty());
        final var primaryConnectionProvider = ConnectionProvider.hikariConnectionProvider(dataSourceConfig, true);
        final var secondaryConnectionProvider = ConnectionProvider.hikariConnectionProvider(dataSourceConfig, true);
        TRANSACTION_MANAGER =
                new TransactionManager(primaryConnectionProvider, secondaryConnectionProvider, SQLDialect.POSTGRES);

        final var flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        REPOSITORY = new DummyRepository(TRANSACTION_MANAGER);
    }

    public PgBaseRepositoryTest() {
        super(DB_CONTAINER, REPOSITORY);
        this.transactionManager = TRANSACTION_MANAGER;
    }
}
