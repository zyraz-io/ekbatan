package io.ekbatan.test.postgres_sharded.test;

import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
public abstract class PgShardedBaseTest {

    // Shard 0: group=0, member=0 ("global")
    @Container
    protected static final PostgreSQLContainer globalDb = new PostgreSQLContainer("postgres:latest")
            .withDatabaseName("global_db")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    // Shard 1: group=1, member=0 ("mexico")
    @Container
    protected static final PostgreSQLContainer mexicoDb = new PostgreSQLContainer("postgres:latest")
            .withDatabaseName("mexico_db")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    protected static final ShardIdentifier GLOBAL_SHARD = ShardIdentifier.of(0, 0);
    protected static final ShardIdentifier MEXICO_SHARD = ShardIdentifier.of(1, 0);

    protected static DatabaseRegistry databaseRegistry;

    @BeforeAll
    static void beforeAll() {
        // Global shard
        var globalConfig = DataSourceConfig.Builder.dataSourceConfig()
                .jdbcUrl(globalDb.getJdbcUrl())
                .username(globalDb.getUsername())
                .password(globalDb.getPassword())
                .maximumPoolSize(10)
                .build();
        var globalTm = new TransactionManager(
                ConnectionProvider.hikariConnectionProvider(globalConfig),
                ConnectionProvider.hikariConnectionProvider(globalConfig),
                SQLDialect.POSTGRES);

        // Mexico shard
        var mexicoConfig = DataSourceConfig.Builder.dataSourceConfig()
                .jdbcUrl(mexicoDb.getJdbcUrl())
                .username(mexicoDb.getUsername())
                .password(mexicoDb.getPassword())
                .maximumPoolSize(10)
                .build();
        var mexicoTm = new TransactionManager(
                ConnectionProvider.hikariConnectionProvider(mexicoConfig),
                ConnectionProvider.hikariConnectionProvider(mexicoConfig),
                SQLDialect.POSTGRES);

        // Registry with two shards
        databaseRegistry = databaseRegistry()
                .withDatabase(GLOBAL_SHARD, globalTm)
                .withDatabase(MEXICO_SHARD, mexicoTm)
                .defaultShard(GLOBAL_SHARD)
                .build();

        // Run migrations on BOTH databases
        Flyway.configure()
                .dataSource(globalDb.getJdbcUrl(), globalDb.getUsername(), globalDb.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        Flyway.configure()
                .dataSource(mexicoDb.getJdbcUrl(), mexicoDb.getUsername(), mexicoDb.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}
