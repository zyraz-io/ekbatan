package io.example.wallet.startup;

import com.zaxxer.hikari.HikariDataSource;
import io.ekbatan.core.shard.config.ShardingConfig;
import javax.sql.DataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Programmatically builds the {@link DataSource} that Spring Boot's
 * {@code FlywayAutoConfiguration} binds Flyway to, from the default shard's
 * {@code primaryConfig} under {@code ekbatan.sharding.*}. Single source of truth: connection
 * coordinates live under one config tree, never duplicated in {@code spring.datasource.*}.
 *
 * <p>{@link FlywayDataSource @FlywayDataSource} scopes this bean to the Flyway
 * auto-configuration only — Spring Boot does NOT pick it up as the application's main
 * {@code DataSource}. Ekbatan keeps owning the application's runtime pools
 * (sharding-aware {@code ConnectionProvider}); this pool exists solely for the Flyway
 * migration burst on startup. Sized at 2 connections because that's all Flyway needs.
 *
 * <p>Why a {@code @Bean} (Spring) instead of a
 * {@code org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer}
 * (Quarkus/Micronaut wallet pattern): Spring Boot's {@code FlywayAutoConfiguration} is
 * gated on {@code @ConditionalOnBean(DataSource.class)} — without a {@code DataSource}
 * bean it never creates a Flyway instance, and the customizer never fires. Producing the
 * {@code DataSource} programmatically from {@code ShardingConfig} satisfies that gate AND
 * provides the right coordinates in one step. A separate customizer would be redundant
 * here (it would override a {@code DataSource} we just built ourselves).
 */
@Configuration
public class EkbatanShardFlywayDataSource {

    @Bean
    @FlywayDataSource
    public DataSource flywayDataSource(ShardingConfig shardingConfig) {
        var primary = shardingConfig.groups.getFirst().members.getFirst().primaryConfig();
        var ds = new HikariDataSource();
        ds.setJdbcUrl(primary.jdbcUrl);
        ds.setUsername(primary.username);
        ds.setPassword(primary.password);
        ds.setMaximumPoolSize(2);
        ds.setPoolName("flyway-migrations");
        return ds;
    }
}
