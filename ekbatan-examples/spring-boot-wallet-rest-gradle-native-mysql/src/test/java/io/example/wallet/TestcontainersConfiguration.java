package io.example.wallet;

import java.nio.file.Path;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/** Boots two database containers and points Ekbatan at them as global + Mexico shards. */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean(name = "globalDatabaseContainer", initMethod = "start", destroyMethod = "stop")
    MySQLContainer globalDatabaseContainer() {
        return new MySQLContainer("mysql:9.4.0")
                .withDatabaseName("wallet")
                .withUsername("wallet")
                .withPassword("wallet")
                .withEnv("TZ", "UTC")
                .withCopyFileToContainer(initScript("mysql_init.sql"), "/docker-entrypoint-initdb.d/mysql_init.sql");
    }

    @Bean(name = "mexicoDatabaseContainer", initMethod = "start", destroyMethod = "stop")
    MySQLContainer mexicoDatabaseContainer() {
        return new MySQLContainer("mysql:9.4.0")
                .withDatabaseName("wallet")
                .withUsername("wallet")
                .withPassword("wallet")
                .withEnv("TZ", "UTC")
                .withCopyFileToContainer(initScript("mysql_init.sql"), "/docker-entrypoint-initdb.d/mysql_init.sql");
    }

    @Bean
    DynamicPropertyRegistrar ekbatanShardingProperties(
            @Qualifier("globalDatabaseContainer") MySQLContainer global,
            @Qualifier("mexicoDatabaseContainer") MySQLContainer mexico) {
        return registry -> {
            registerShard(
                    registry,
                    "ekbatan.sharding.groups[0].members[0]",
                    global::getJdbcUrl,
                    global::getUsername,
                    global::getPassword,
                    "com.mysql.cj.jdbc.Driver");
            registerShard(
                    registry,
                    "ekbatan.sharding.groups[1].members[0]",
                    mexico::getJdbcUrl,
                    mexico::getUsername,
                    mexico::getPassword,
                    "com.mysql.cj.jdbc.Driver");
        };
    }

    private static MountableFile initScript(String filename) {
        return MountableFile.forHostPath(Path.of("src/main/resources", filename).toAbsolutePath());
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
        addDataSource(registry, prefix + ".configs.lockConfig", jdbcUrl, username, password, driverClassName, 15);
        registry.add(prefix + ".configs.lockConfig.leakDetectionThreshold", () -> "0");
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
