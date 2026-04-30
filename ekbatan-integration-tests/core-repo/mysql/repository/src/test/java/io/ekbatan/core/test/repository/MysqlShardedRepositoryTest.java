package io.ekbatan.core.test.repository;

import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.repository.AbstractRepository;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.test.model.Dummy;
import io.ekbatan.graalvm.flyway.FlywayHelper;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
public class MysqlShardedRepositoryTest extends BaseShardedRepositoryTest {

    @Container
    private static final MySQLContainer SHARD_A_CONTAINER = new MySQLContainer("mysql:9.4.0")
            .withDatabaseName("shard_a_db")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    @Container
    private static final MySQLContainer SHARD_B_CONTAINER = new MySQLContainer("mysql:9.4.0")
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

        DATABASE_REGISTRY =
                databaseRegistry().withDefaultDatabase(tmA).withDatabase(tmB).build();

        runMigrations(SHARD_A_CONTAINER);
        runMigrations(SHARD_B_CONTAINER);

        REPOSITORY = new ShardedDummyRepository(DATABASE_REGISTRY);
    }

    public MysqlShardedRepositoryTest() {
        super(DATABASE_REGISTRY, REPOSITORY);
    }

    @Override
    protected AbstractRepository<Dummy, ?, ?, UUID> createRepository(DatabaseRegistry registry) {
        return new ShardedDummyRepository(registry);
    }

    private static TransactionManager createTransactionManager(
            MySQLContainer container, io.ekbatan.core.shard.ShardIdentifier shard) {
        var config = DataSourceConfig.Builder.dataSourceConfig()
                .jdbcUrl(container.getJdbcUrl())
                .username(container.getUsername())
                .password(container.getPassword())
                .maximumPoolSize(10)
                .build();
        var primaryConnectionProvider = ConnectionProvider.hikariConnectionProvider(config);
        var secondaryConnectionProvider = ConnectionProvider.hikariConnectionProvider(config);
        return new TransactionManager(primaryConnectionProvider, secondaryConnectionProvider, SQLDialect.MYSQL, shard);
    }

    private static void runMigrations(MySQLContainer container) {
        FlywayHelper.migrate(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }
}
