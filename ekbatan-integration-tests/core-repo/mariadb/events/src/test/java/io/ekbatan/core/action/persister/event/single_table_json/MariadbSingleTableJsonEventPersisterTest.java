package io.ekbatan.core.action.persister.event.single_table_json;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.test.testcontainers.ClasspathTransferable;
import io.ekbatan.graalvm.flyway.FlywayHelper;
import org.jooq.SQLDialect;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

@Testcontainers
class MariadbSingleTableJsonEventPersisterTest extends BaseSingleTableJsonEventPersisterTest {

    @Container
    private static final MariaDBContainer DB_CONTAINER = new MariaDBContainer("mariadb:11.7")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withCopyToContainer(
                    ClasspathTransferable.of("mariadb_init.sql"), "/docker-entrypoint-initdb.d/mariadb_init.sql")
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

        FlywayHelper.migrate(jdbcUrl, username, password);
    }

    MariadbSingleTableJsonEventPersisterTest() {
        super(TRANSACTION_MANAGER);
    }
}
