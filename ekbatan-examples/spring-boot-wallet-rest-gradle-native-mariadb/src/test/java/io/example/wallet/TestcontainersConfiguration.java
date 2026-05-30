package io.example.wallet;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.mariadb.MariaDBContainer;

/**
 * Boots a MariaDB testcontainer and points Ekbatan's sharding config at it. Migrations are NOT run
 * here - {@link FlywayConfiguration} already owns that and runs against the same
 * {@link io.ekbatan.core.config.ShardingConfig} this registrar populates, so the same code
 * path serves both production and tests.
 *
 * <p>The {@code mariadb_init.sql} resource is copied into {@code /docker-entrypoint-initdb.d/} on
 * container startup. The image's entrypoint executes every {@code .sql} file there as root
 * <em>before</em> the database becomes ready - which is the only place we can {@code GRANT}
 * cross-database privileges to the {@code wallet} user. Without this, the V0000 Flyway migration
 * {@code CREATE DATABASE eventlog} fails with an access-denied error.
 *
 * <p>Bean-based wiring (rather than {@code @Container} + {@code @DynamicPropertySource}) is the
 * Spring AOT / native-image friendly pattern - the lambda runs at context refresh, so the JDBC
 * URL reflects whatever port Docker assigned for this run.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    MariaDBContainer mariadbContainer() {
        return new MariaDBContainer("mariadb:11.8")
                .withDatabaseName("wallet")
                .withUsername("wallet")
                .withPassword("wallet")
                .withEnv("TZ", "UTC")
                .withCopyToContainer(
                        Transferable.of("""
-- The named test user (`wallet`) only has rights on the `wallet` database by default. The
                                    -- first Flyway migration needs to CREATE DATABASE eventlog and then write tables into it, so
                                    -- we grant cross-database privileges here. This script runs as root, before the container
                                    -- becomes ready, so subsequent migrations run as `wallet` with full access.
                                    GRANT ALL PRIVILEGES ON *.* TO 'wallet'@'%';
                                    FLUSH PRIVILEGES;
                                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "/docker-entrypoint-initdb.d/mariadb_init.sql");
    }

    @Bean
    DynamicPropertyRegistrar ekbatanShardingProperties(MariaDBContainer mariadb) {
        return registry -> {
            registry.add("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl", mariadb::getJdbcUrl);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username", mariadb::getUsername);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password", mariadb::getPassword);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.jdbcUrl", mariadb::getJdbcUrl);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.username", mariadb::getUsername);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.password", mariadb::getPassword);
        };
    }
}
