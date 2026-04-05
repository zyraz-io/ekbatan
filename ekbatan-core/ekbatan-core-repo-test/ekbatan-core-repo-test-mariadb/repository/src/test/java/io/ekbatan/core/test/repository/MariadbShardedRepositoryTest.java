package io.ekbatan.core.test.repository;

import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.repository.AbstractRepository;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.test.model.Dummy;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

@Testcontainers
public class MariadbShardedRepositoryTest extends BaseShardedRepositoryTest {

    @Container
    private static final MariaDBContainer SHARD_A_CONTAINER = new MariaDBContainer("mariadb:latest")
            .withDatabaseName("shard_a_db")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    @Container
    private static final MariaDBContainer SHARD_B_CONTAINER = new MariaDBContainer("mariadb:latest")
            .withDatabaseName("shard_b_db")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    private static final DatabaseRegistry DATABASE_REGISTRY;
    private static final ShardedDummyRepository REPOSITORY;

    static {
        SHARD_A_CONTAINER.start();
        SHARD_B_CONTAINER.start();

        var tmA = createTransactionManager(SHARD_A_CONTAINER, SHARD_A);
        var tmB = createTransactionManager(SHARD_B_CONTAINER, SHARD_B);

        DATABASE_REGISTRY = databaseRegistry()
                .withDatabase(tmA.shardIdentifier, tmA)
                .withDatabase(tmB.shardIdentifier, tmB)
                .defaultShard(SHARD_A)
                .build();

        runMigrations(SHARD_A_CONTAINER);
        runMigrations(SHARD_B_CONTAINER);

        REPOSITORY = new ShardedDummyRepository(DATABASE_REGISTRY);
    }

    public MariadbShardedRepositoryTest() {
        super(DATABASE_REGISTRY, REPOSITORY);
    }

    @Override
    protected AbstractRepository<Dummy, ?, ?, UUID> createRepository(DatabaseRegistry registry) {
        return new ShardedDummyRepository(registry);
    }

    private static TransactionManager createTransactionManager(
            MariaDBContainer container, io.ekbatan.core.shard.ShardIdentifier shard) {
        var config = DataSourceConfig.Builder.dataSourceConfig()
                .jdbcUrl(container.getJdbcUrl())
                .username(container.getUsername())
                .password(container.getPassword())
                .maximumPoolSize(10)
                .build();
        var primaryConnectionProvider = ConnectionProvider.hikariConnectionProvider(config);
        var secondaryConnectionProvider = ConnectionProvider.hikariConnectionProvider(config);
        return new TransactionManager(
                primaryConnectionProvider, secondaryConnectionProvider, SQLDialect.MARIADB, shard);
    }

    private static void runMigrations(MariaDBContainer container) {
        Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}
