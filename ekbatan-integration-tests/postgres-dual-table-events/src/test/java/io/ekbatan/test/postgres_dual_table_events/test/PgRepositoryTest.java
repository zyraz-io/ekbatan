package io.ekbatan.test.postgres_dual_table_events.test;

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
public abstract class PgRepositoryTest {

    @Container
    protected static final PostgreSQLContainer db = new PostgreSQLContainer("postgres:latest")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    protected static TransactionManager transactionManager;

    @BeforeAll
    static void beforeAll() {
        // Initialize database connection
        String jdbcUrl = db.getJdbcUrl();
        String username = db.getUsername();
        String password = db.getPassword();

        var dataSourceConfig = DataSourceConfig.Builder.dataSourceConfig()
                .jdbcUrl(jdbcUrl)
                .username(username)
                .password(password)
                .maximumPoolSize(10)
                .build();
        var primaryConnectionProvider = ConnectionProvider.hikariConnectionProvider(dataSourceConfig);
        var secondaryConnectionProvider = ConnectionProvider.hikariConnectionProvider(dataSourceConfig);
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
