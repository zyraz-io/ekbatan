package io.ekbatan.core.test.repository;

import static java.util.Optional.empty;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

@Testcontainers
public abstract class MariadbBaseRepositoryTest {

    @Container
    protected static final MariaDBContainer db = new MariaDBContainer("mariadb:latest")
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
        var secondaryConnectionProvider = ConnectionProvider.hikariConnectionProvider(dataSourceConfig, true);
        transactionManager =
                new TransactionManager(primaryConnectionProvider, secondaryConnectionProvider, SQLDialect.MARIADB);

        // exec migrations
        var flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
    }
}
