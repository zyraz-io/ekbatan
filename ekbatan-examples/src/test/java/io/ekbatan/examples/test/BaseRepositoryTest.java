package io.ekbatan.examples.test;

import static java.util.Optional.empty;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public abstract class BaseRepositoryTest {

    @Container
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    protected static TransactionManager transactionManager;

    @BeforeAll
    static void beforeAll() {
        // Initialize database connection
        String jdbcUrl = postgres.getJdbcUrl();
        String username = postgres.getUsername();
        String password = postgres.getPassword();

        var dataSourceConfig =
                new DataSourceConfig(jdbcUrl, username, password, empty(), 10, empty(), empty(), empty());
        var primaryConnectionProvider = ConnectionProvider.hikariConnectionProvider(dataSourceConfig, true);
        var secondaryConnectionProvider = ConnectionProvider.hikariConnectionProvider(dataSourceConfig, true);
        transactionManager = new TransactionManager(primaryConnectionProvider, secondaryConnectionProvider);

        // exec migrations
        var flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
    }
}
