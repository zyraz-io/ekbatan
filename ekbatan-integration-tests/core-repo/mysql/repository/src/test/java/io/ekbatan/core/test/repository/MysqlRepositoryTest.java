package io.ekbatan.core.test.repository;

import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
public class MysqlRepositoryTest extends BaseRepositoryTest {

    @Container
    private static final MySQLContainer DB_CONTAINER = new MySQLContainer("mysql:9.4.0")
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
                new TransactionManager(primaryConnectionProvider, secondaryConnectionProvider, SQLDialect.MYSQL);

        final var flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        var databaseRegistry =
                databaseRegistry().withDatabase(TRANSACTION_MANAGER).build();
        REPOSITORY = new DummyRepository(databaseRegistry);
    }

    public MysqlRepositoryTest() {
        super(DB_CONTAINER, REPOSITORY);
        this.transactionManager = TRANSACTION_MANAGER;
    }
}
