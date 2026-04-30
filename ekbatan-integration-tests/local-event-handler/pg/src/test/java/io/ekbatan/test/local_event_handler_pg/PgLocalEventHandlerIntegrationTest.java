package io.ekbatan.test.local_event_handler_pg;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;
import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;

import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.graalvm.flyway.FlywayHelper;
import io.ekbatan.test.local_event_handler.BaseLocalEventHandlerIntegrationTest;
import io.ekbatan.test.local_event_handler_pg.audit.repository.AuditEntryRepository;
import io.ekbatan.test.local_event_handler_pg.note.repository.NoteRepository;
import io.ekbatan.test.local_event_handler_pg.widget.repository.WidgetRepository;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.jooq.SQLDialect;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PgLocalEventHandlerIntegrationTest extends BaseLocalEventHandlerIntegrationTest {

    private static final ShardIdentifier SHARD_0 = ShardIdentifier.DEFAULT;
    private static final ShardIdentifier SHARD_1 = ShardIdentifier.of(1, 0);

    @Container
    private static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:latest")
            .withDatabaseName("shard_0")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    private static final Map<ShardIdentifier, ConnectionProvider> CONNECTION_PROVIDERS;
    private static final DatabaseRegistry DB_REGISTRY;

    static {
        DB.start();

        // Create the second physical database within the same container. The test user is
        // a superuser so it has CREATEDB by default.
        try (var rootConn = DriverManager.getConnection(
                        DB.getJdbcUrl().replace("/shard_0", "/postgres"), DB.getUsername(), DB.getPassword());
                var stmt = rootConn.createStatement()) {
            stmt.execute("CREATE DATABASE shard_1");
        } catch (Exception e) {
            throw new RuntimeException("Failed to create shard_1 database", e);
        }

        var shard0Url = DB.getJdbcUrl();
        var shard1Url = DB.getJdbcUrl().replace("/shard_0", "/shard_1");

        var shard0Cp = hikariConnectionProvider(dataSourceConfig()
                .jdbcUrl(shard0Url)
                .username(DB.getUsername())
                .password(DB.getPassword())
                .maximumPoolSize(8)
                .build());
        var shard1Cp = hikariConnectionProvider(dataSourceConfig()
                .jdbcUrl(shard1Url)
                .username(DB.getUsername())
                .password(DB.getPassword())
                .maximumPoolSize(8)
                .build());
        CONNECTION_PROVIDERS = Map.of(SHARD_0, shard0Cp, SHARD_1, shard1Cp);

        FlywayHelper.migrate(shard0Url, DB.getUsername(), DB.getPassword());
        FlywayHelper.migrate(shard1Url, DB.getUsername(), DB.getPassword());

        var shard0Tm = new TransactionManager(shard0Cp, shard0Cp, SQLDialect.POSTGRES, SHARD_0);
        var shard1Tm = new TransactionManager(shard1Cp, shard1Cp, SQLDialect.POSTGRES, SHARD_1);

        DB_REGISTRY = databaseRegistry()
                .withDefaultDatabase(shard0Tm)
                .withDatabase(shard1Tm)
                .build();
    }

    PgLocalEventHandlerIntegrationTest() {
        super(
                DB_REGISTRY,
                List.of(SHARD_0, SHARD_1),
                new WidgetRepository(DB_REGISTRY),
                new NoteRepository(DB_REGISTRY),
                new AuditEntryRepository(DB_REGISTRY));
    }

    @Override
    protected void truncateAllTables() throws Exception {
        for (var cp : CONNECTION_PROVIDERS.values()) {
            try (var conn = cp.acquire();
                    var stmt = conn.createStatement()) {
                stmt.execute(
                        "TRUNCATE TABLE eventlog.event_notifications, eventlog.events, widgets, notes, audit_entries");
            }
        }
    }

    @Override
    protected long countNotificationsOnShard(String state, ShardIdentifier shard) throws Exception {
        try (var conn = CONNECTION_PROVIDERS.get(shard).acquire();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM eventlog.event_notifications WHERE state = '" + state + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Override
    protected long countEventsDeliveredOnShard(boolean delivered, ShardIdentifier shard) throws Exception {
        try (var conn = CONNECTION_PROVIDERS.get(shard).acquire();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery("SELECT COUNT(*) FROM eventlog.events WHERE delivered = " + delivered)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
