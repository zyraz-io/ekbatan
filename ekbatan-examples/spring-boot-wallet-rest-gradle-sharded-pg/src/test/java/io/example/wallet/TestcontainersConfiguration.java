package io.example.wallet;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots two Postgres testcontainers - one per shard - and points Ekbatan's sharding config at
 * them. Migrations are not run here; {@code EkbatanShardFlywayMigrator} iterates every shard's
 * {@code primaryConfig} from the same {@link io.ekbatan.core.shard.config.ShardingConfig}
 * this registrar populates.
 *
 * <p>The bean names ({@code globalPostgres} / {@code mexicoPostgres}) are deliberately distinct
 * so the {@code DynamicPropertyRegistrar} can pull each one's host:port without ambiguity.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    PostgreSQLContainer globalPostgres() {
        return new PostgreSQLContainer("postgres:17")
                .withDatabaseName("wallet_global")
                .withUsername("wallet")
                .withPassword("wallet")
                .withEnv("TZ", "UTC");
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    PostgreSQLContainer mexicoPostgres() {
        return new PostgreSQLContainer("postgres:17")
                .withDatabaseName("wallet_mexico")
                .withUsername("wallet")
                .withPassword("wallet")
                .withEnv("TZ", "UTC");
    }

    @Bean
    DynamicPropertyRegistrar ekbatanShardingProperties(
            PostgreSQLContainer globalPostgres, PostgreSQLContainer mexicoPostgres) {
        return registry -> {
            // groups[0] = global shard (group=0, member=0) - the default.
            registry.add(
                    "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl", globalPostgres::getJdbcUrl);
            registry.add(
                    "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username",
                    globalPostgres::getUsername);
            registry.add(
                    "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password",
                    globalPostgres::getPassword);

            // groups[1] = mexico shard (group=1, member=0).
            registry.add(
                    "ekbatan.sharding.groups[1].members[0].configs.primaryConfig.jdbcUrl", mexicoPostgres::getJdbcUrl);
            registry.add(
                    "ekbatan.sharding.groups[1].members[0].configs.primaryConfig.username",
                    mexicoPostgres::getUsername);
            registry.add(
                    "ekbatan.sharding.groups[1].members[0].configs.primaryConfig.password",
                    mexicoPostgres::getPassword);
        };
    }
}
