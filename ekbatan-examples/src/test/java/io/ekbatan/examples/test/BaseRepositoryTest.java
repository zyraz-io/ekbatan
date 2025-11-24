package io.ekbatan.examples.test;

import static java.util.Optional.empty;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.persistence.connection.ConnectionProvider;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;

public abstract class BaseRepositoryTest {

    protected static PostgreSQLContainer<?> postgres;
    protected static TransactionManager transactionManager;

    @BeforeAll
    static void beforeAll() {
        // Start the container
        postgres = new PostgreSQLContainer<>("postgres:15-alpine")
                .withDatabaseName("testdb")
                .withUsername("test")
                .withPassword("test");
        postgres.start();

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

    @AfterAll
    static void afterAll() throws SQLException {
        // Clean up resources
        if (postgres != null) {
            postgres.stop();
        }
    }
}
