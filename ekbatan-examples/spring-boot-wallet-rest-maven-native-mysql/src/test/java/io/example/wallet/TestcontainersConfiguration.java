package io.example.wallet;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Boots a testcontainer and points Ekbatan's sharding config at it. Migrations are NOT
 * run here — Spring Boot's {@code FlywayAutoConfiguration} owns that at context startup;
 * {@code EkbatanShardFlywayCustomizer} builds Flyway's @FlywayDataSource bean from the same
 * {@link io.ekbatan.core.shard.config.ShardingConfig} this registrar populates, so the same
 * code path serves both production and tests.
 *
 * <p>Bean-based wiring (rather than {@code @Container} + {@code @DynamicPropertySource}) is the
 * Spring AOT / native-image friendly pattern — the lambda runs at context refresh, so the JDBC
 * URL reflects whatever port Docker assigned for this run.
 *
 * <p>The {@code mysql_init.sql} bind-mount is what the V0000 migration needs: it grants
 * cross-database privileges to the {@code wallet} user so {@code CREATE DATABASE eventlog}
 * succeeds at Flyway's first-migration phase. The script runs as root on container startup,
 * before the named user connects.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    MySQLContainer mysqlContainer() {
        return new MySQLContainer("mysql:9.4.0")
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
                        "/docker-entrypoint-initdb.d/mysql_init.sql");
    }

    @Bean
    DynamicPropertyRegistrar ekbatanShardingProperties(MySQLContainer mysql) {
        return registry -> {
            registry.add("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl", mysql::getJdbcUrl);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username", mysql::getUsername);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password", mysql::getPassword);
            registry.add(
                    "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.driverClassName",
                    () -> "com.mysql.cj.jdbc.Driver");
            registry.add("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.jdbcUrl", mysql::getJdbcUrl);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.username", mysql::getUsername);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.password", mysql::getPassword);
            registry.add(
                    "ekbatan.sharding.groups[0].members[0].configs.jobsConfig.driverClassName",
                    () -> "com.mysql.cj.jdbc.Driver");
        };
    }
}
