package io.ekbatan.test.local_event_handler_mysql;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;
import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;

import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.core.test.testcontainers.ClasspathTransferable;
import io.ekbatan.graalvm.flyway.FlywayHelper;
import io.ekbatan.test.local_event_handler.BaseLocalEventHandlerIntegrationTest;
import io.ekbatan.test.local_event_handler_mysql.audit.repository.AuditEntryRepository;
import io.ekbatan.test.local_event_handler_mysql.note.repository.NoteRepository;
import io.ekbatan.test.local_event_handler_mysql.widget.repository.WidgetRepository;
import java.util.List;
import org.jooq.SQLDialect;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class MysqlLocalEventHandlerIntegrationTest extends BaseLocalEventHandlerIntegrationTest {

    @Container
    private static final MySQLContainer DB = new MySQLContainer("mysql:9.4.0")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withCopyToContainer(
                    ClasspathTransferable.of("mysql_init.sql"), "/docker-entrypoint-initdb.d/mysql_init.sql")
            .withEnv("TZ", "UTC");

    private static final ConnectionProvider CONNECTION_PROVIDER;
    private static final DatabaseRegistry DB_REGISTRY;

    static {
        DB.start();

        var config = dataSourceConfig()
                .jdbcUrl(DB.getJdbcUrl())
                .username(DB.getUsername())
                .password(DB.getPassword())
                .maximumPoolSize(8)
                .build();
        CONNECTION_PROVIDER = hikariConnectionProvider(config);

        FlywayHelper.migrate(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());

        var tm = new TransactionManager(CONNECTION_PROVIDER, CONNECTION_PROVIDER, SQLDialect.MYSQL);
        DB_REGISTRY = databaseRegistry().withDatabase(tm).build();
    }

    MysqlLocalEventHandlerIntegrationTest() {
        super(
                DB_REGISTRY,
                List.of(ShardIdentifier.DEFAULT),
                new WidgetRepository(DB_REGISTRY),
                new NoteRepository(DB_REGISTRY),
                new AuditEntryRepository(DB_REGISTRY));
    }

    @Override
    protected void truncateAllTables() throws Exception {
        try (var conn = CONNECTION_PROVIDER.acquire();
                var stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE eventlog.event_notifications");
            stmt.execute("TRUNCATE TABLE eventlog.events");
            stmt.execute("TRUNCATE TABLE widgets");
            stmt.execute("TRUNCATE TABLE notes");
            stmt.execute("TRUNCATE TABLE audit_entries");
        }
    }

    @Override
    protected long countNotificationsOnShard(String state, ShardIdentifier shard) throws Exception {
        try (var conn = CONNECTION_PROVIDER.acquire();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM eventlog.event_notifications WHERE state = '" + state + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Override
    protected long countEventsDeliveredOnShard(boolean delivered, ShardIdentifier shard) throws Exception {
        try (var conn = CONNECTION_PROVIDER.acquire();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery("SELECT COUNT(*) FROM eventlog.events WHERE delivered = " + delivered)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
