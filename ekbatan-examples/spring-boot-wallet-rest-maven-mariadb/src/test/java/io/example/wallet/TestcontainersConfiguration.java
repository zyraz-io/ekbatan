package io.example.wallet;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Boots a testcontainer and points Ekbatan's sharding config at it. Migrations are NOT
 * run here - Spring Boot's {@code FlywayAutoConfiguration} owns that at context startup;
 * {@code EkbatanShardFlywayCustomizer} builds Flyway's @FlywayDataSource bean from the same
 * {@link io.ekbatan.core.shard.config.ShardingConfig} this registrar populates, so the same
 * code path serves both production and tests.
 *
 * <p>Bean-based wiring (rather than {@code @Container} + {@code @DynamicPropertySource}) is the
 * Spring AOT / native-image friendly pattern - the lambda runs at context refresh, so the JDBC
 * URL reflects whatever port Docker assigned for this run.
 *
 * <p>The {@code mariadb_init.sql} bind-mount is what the V0000 migration needs: it grants
 * cross-database privileges to the {@code wallet} user so {@code CREATE DATABASE eventlog}
 * succeeds at Flyway's first-migration phase. The script runs as root on container startup,
 * before the named user connects.
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
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("mariadb_init.sql"),
                        "/docker-entrypoint-initdb.d/mariadb_init.sql");
    }

    @Bean
    DynamicPropertyRegistrar ekbatanShardingProperties(MariaDBContainer mariadb) {
        return registry -> {
            registry.add("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl", mariadb::getJdbcUrl);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username", mariadb::getUsername);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password", mariadb::getPassword);
            registry.add(
                    "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.driverClassName",
                    () -> "org.mariadb.jdbc.Driver");
            registry.add("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.jdbcUrl", mariadb::getJdbcUrl);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.username", mariadb::getUsername);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.password", mariadb::getPassword);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.lockConfig.jdbcUrl", mariadb::getJdbcUrl);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.lockConfig.username", mariadb::getUsername);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.lockConfig.password", mariadb::getPassword);
            registry.add(
                    "ekbatan.sharding.groups[0].members[0].configs.jobsConfig.driverClassName",
                    () -> "org.mariadb.jdbc.Driver");
        };
    }
}
