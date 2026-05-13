package io.example.wallet;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:17")
                .withDatabaseName("wallet")
                .withUsername("wallet")
                .withPassword("wallet")
                .withEnv("TZ", "UTC");
    }

    @Bean
    DynamicPropertyRegistrar ekbatanShardingProperties(PostgreSQLContainer postgres) {
        return registry -> {
            registry.add("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl", postgres::getJdbcUrl);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username", postgres::getUsername);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password", postgres::getPassword);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.jdbcUrl", postgres::getJdbcUrl);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.username", postgres::getUsername);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.password", postgres::getPassword);
        };
    }
}
