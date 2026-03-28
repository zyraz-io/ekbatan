package io.ekbatan.core.action.persister.event.single_table;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class MariadbSingleTableEventPersisterTest extends BaseSingleTableEventPersisterTest {

    @Container
    private static final MariaDBContainer DB_CONTAINER = new MariaDBContainer("mariadb:11.7")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("mariadb_init.sql"),
                    "/docker-entrypoint-initdb.d/mariadb_init.sql")
            .withEnv("TZ", "UTC");

    private static final TransactionManager TRANSACTION_MANAGER;

    static {
        DB_CONTAINER.start();

        var jdbcUrl = DB_CONTAINER.getJdbcUrl();
        var username = DB_CONTAINER.getUsername();
        var password = DB_CONTAINER.getPassword();

        var dataSourceConfig = DataSourceConfig.Builder.dataSourceConfig()
                .jdbcUrl(jdbcUrl)
                .username(username)
                .password(password)
                .maximumPoolSize(10)
                .build();
        var primaryConnectionProvider = ConnectionProvider.hikariConnectionProvider(dataSourceConfig);
        var secondaryConnectionProvider = ConnectionProvider.hikariConnectionProvider(dataSourceConfig);
        TRANSACTION_MANAGER =
                new TransactionManager(primaryConnectionProvider, secondaryConnectionProvider, SQLDialect.MARIADB);

        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    MariadbSingleTableEventPersisterTest() {
        super(TRANSACTION_MANAGER);
    }
}
