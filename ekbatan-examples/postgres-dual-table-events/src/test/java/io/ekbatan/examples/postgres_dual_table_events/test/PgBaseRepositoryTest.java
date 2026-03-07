package io.ekbatan.examples.postgres_dual_table_events.test;

import static java.util.Optional.empty;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
public abstract class PgBaseRepositoryTest {

    @Container
    protected static final PostgreSQLContainer db = new PostgreSQLContainer("postgres:latest")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    protected static TransactionManager transactionManager;

    @BeforeAll
    static void beforeAll() {
        // Initialize database connection
        String jdbcUrl = db.getJdbcUrl();
        String username = db.getUsername();
        String password = db.getPassword();

        var dataSourceConfig =
                new DataSourceConfig(jdbcUrl, username, password, empty(), 10, empty(), empty(), empty());
        var primaryConnectionProvider = ConnectionProvider.hikariConnectionProvider(dataSourceConfig, true);
        var secondaryConnectionProvider = ConnectionProvider.hikariConnectionProvider(dataSourceConfig, false);
        transactionManager =
                new TransactionManager(primaryConnectionProvider, secondaryConnectionProvider, SQLDialect.POSTGRES);

        // exec migrations
        var flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
    }
}
