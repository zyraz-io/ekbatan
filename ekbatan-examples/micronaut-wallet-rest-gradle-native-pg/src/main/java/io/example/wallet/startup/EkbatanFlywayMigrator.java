package io.example.wallet.startup;

import io.ekbatan.core.config.ShardingConfig;
import io.ekbatan.flyway.FlywayMigrator;
import io.micronaut.context.annotation.Context;

/**
 * Runs Flyway migrations at application startup using connection coordinates from
 * {@code ekbatan.sharding.*} - single source of truth, no separate {@code flyway.*}
 * YAML block, no Micronaut Flyway customizer.
 *
 * <p>{@link FlywayMigrator} detects GraalVM native-image at runtime and keeps
 * {@code classpath:db/migration} resources walkable inside the native binary.
 *
 * <p>Marked {@code @Context} so Micronaut instantiates it eagerly during application
 * startup (before lazy {@code @Singleton} beans like Ekbatan's {@code DatabaseRegistry}),
 * guaranteeing migrations are applied before anything else touches the database.
 */
@Context
public class EkbatanFlywayMigrator {

    public EkbatanFlywayMigrator(ShardingConfig shardingConfig) {
        FlywayMigrator.migrate(shardingConfig);
    }
}
