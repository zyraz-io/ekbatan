package io.example.wallet.startup;

import io.ekbatan.core.shard.config.ShardingConfig;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.flyway.FlywayConfigurationCustomizer;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * Points {@code micronaut-flyway} at the default shard's {@code primaryConfig} so there is a
 * single source of truth for connection coordinates: {@code ekbatan.sharding.*}. Without this
 * customizer, the {@code micronaut-flyway} extension would migrate against whatever
 * {@code flyway.datasources.default.{url, user, password}} resolves to — which is fine, but
 * having a {@link FlywayConfigurationCustomizer} bean keeps the source of truth typed
 * (a {@link ShardingConfig} record) and self-documents the choice in Java code rather than
 * inside a property-expression chain.
 *
 * <p>{@code @Named("default")} matches the {@code flyway.datasources.default} key in
 * {@code application.yml} — Micronaut wires the customizer to that specific Flyway instance
 * via {@code Qualifiers.byName("default")}. {@link FlywayConfigurationCustomizer} also extends
 * {@code io.micronaut.core.naming.Named}, so {@link #getName()} is implemented for the
 * interface contract; both the annotation and the method return the same qualifier.
 *
 * <p>The framework calls {@link #customizeFluentConfiguration(FluentConfiguration)} after
 * applying YAML config but before building the {@code Flyway} instance and running
 * migrations — at which point we call
 * {@link FluentConfiguration#dataSource(String, String, String)} to override whatever was
 * bound from YAML.
 *
 * <p>Mirrors the Quarkus wallet's {@code EkbatanShardFlywayCustomizer} (CDI-based) — same
 * intent, different framework hook.
 */
@Singleton
@Named("default")
public class EkbatanShardFlywayCustomizer implements FlywayConfigurationCustomizer {

    private final ShardingConfig shardingConfig;

    public EkbatanShardFlywayCustomizer(ShardingConfig shardingConfig) {
        this.shardingConfig = shardingConfig;
    }

    @Override
    @NonNull
    public String getName() {
        return "default";
    }

    @Override
    public void customizeFluentConfiguration(FluentConfiguration configuration) {
        var primary = shardingConfig.groups.getFirst().members.getFirst().primaryConfig();
        configuration.dataSource(primary.jdbcUrl, primary.username, primary.password);
    }
}
