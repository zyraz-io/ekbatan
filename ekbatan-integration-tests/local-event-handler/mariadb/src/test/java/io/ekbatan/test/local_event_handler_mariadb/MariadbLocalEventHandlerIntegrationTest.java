package io.ekbatan.test.local_event_handler_mariadb;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;
import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;

import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.test.local_event_handler.BaseLocalEventHandlerIntegrationTest;
import io.ekbatan.test.local_event_handler_mariadb.audit.repository.AuditEntryRepository;
import io.ekbatan.test.local_event_handler_mariadb.note.repository.NoteRepository;
import io.ekbatan.test.local_event_handler_mariadb.widget.repository.WidgetRepository;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class MariadbLocalEventHandlerIntegrationTest extends BaseLocalEventHandlerIntegrationTest {

    @Container
    private static final MariaDBContainer DB = new MariaDBContainer("mariadb:11.8")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("mariadb_init.sql"),
                    "/docker-entrypoint-initdb.d/mariadb_init.sql")
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

        Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        var tm = new TransactionManager(CONNECTION_PROVIDER, CONNECTION_PROVIDER, SQLDialect.MARIADB);
        DB_REGISTRY = databaseRegistry().withDatabase(tm).build();
    }

    MariadbLocalEventHandlerIntegrationTest() {
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
