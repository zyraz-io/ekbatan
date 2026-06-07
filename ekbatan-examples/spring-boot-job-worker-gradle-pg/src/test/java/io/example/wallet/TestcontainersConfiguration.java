package io.example.wallet;

import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Boots two database containers and points Ekbatan at them as global + Mexico shards. */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean(name = "globalDatabaseContainer", initMethod = "start", destroyMethod = "stop")
    PostgreSQLContainer globalDatabaseContainer() {
        return new PostgreSQLContainer("postgres:17")
                .withDatabaseName("wallet_global")
                .withUsername("wallet")
                .withPassword("wallet")
                .withEnv("TZ", "UTC");
    }

    @Bean(name = "mexicoDatabaseContainer", initMethod = "start", destroyMethod = "stop")
    PostgreSQLContainer mexicoDatabaseContainer() {
        return new PostgreSQLContainer("postgres:17")
                .withDatabaseName("wallet_mexico")
                .withUsername("wallet")
                .withPassword("wallet")
                .withEnv("TZ", "UTC");
    }

    @Bean
    DynamicPropertyRegistrar ekbatanShardingProperties(
            @Qualifier("globalDatabaseContainer") PostgreSQLContainer global,
            @Qualifier("mexicoDatabaseContainer") PostgreSQLContainer mexico) {
        return registry -> {
            registerShard(registry, "ekbatan.sharding.groups[0].members[0]", global::getJdbcUrl, global::getUsername,
                    global::getPassword, "org.postgresql.Driver");
            registerShard(registry, "ekbatan.sharding.groups[1].members[0]", mexico::getJdbcUrl, mexico::getUsername,
                    mexico::getPassword, "org.postgresql.Driver");
        };
    }

    private static void registerShard(
            DynamicPropertyRegistry registry,
            String prefix,
            Supplier<String> jdbcUrl,
            Supplier<String> username,
            Supplier<String> password,
            String driverClassName) {
        addDataSource(registry, prefix + ".configs.primaryConfig", jdbcUrl, username, password, driverClassName, 5);
        addDataSource(registry, prefix + ".configs.jobsConfig", jdbcUrl, username, password, driverClassName, 4);
    }

    private static void addDataSource(
            DynamicPropertyRegistry registry,
            String prefix,
            Supplier<String> jdbcUrl,
            Supplier<String> username,
            Supplier<String> password,
            String driverClassName,
            int maximumPoolSize) {
        registry.add(prefix + ".jdbcUrl", () -> jdbcUrl.get());
        registry.add(prefix + ".username", () -> username.get());
        registry.add(prefix + ".password", () -> password.get());
        registry.add(prefix + ".driverClassName", () -> driverClassName);
        registry.add(prefix + ".maximumPoolSize", () -> Integer.toString(maximumPoolSize));
    }
}
