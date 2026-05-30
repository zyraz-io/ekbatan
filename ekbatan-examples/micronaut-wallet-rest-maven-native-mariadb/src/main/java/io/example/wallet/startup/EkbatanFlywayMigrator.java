package io.example.wallet.startup;

import io.ekbatan.core.config.ShardingConfig;
import io.ekbatan.graalvm.flyway.FlywayHelper;
import io.micronaut.context.annotation.Context;

/**
 * Runs Flyway migrations at application startup using connection coordinates from
 * {@code ekbatan.sharding.*} - single source of truth, no separate {@code flyway.*}
 * YAML block, no {@code micronaut-flyway} auto-configured beans.
 *
 * <p>Native variant: delegates to {@link FlywayHelper#migrate(String, String, String)}
 * which detects GraalVM native-image at runtime and swaps the default
 * {@code ClassPathScanner} for {@code NativeImageFlywayResourceProvider} so classpath
 * migrations are still walkable through Substrate's {@code resource:/} NIO filesystem.
 *
 * <p>Marked {@code @Context} so Micronaut instantiates it eagerly during application
 * startup (before lazy {@code @Singleton} beans like Ekbatan's {@code DatabaseRegistry}),
 * guaranteeing migrations are applied before anything else touches the database.
 */
@Context
public class EkbatanFlywayMigrator {

    public EkbatanFlywayMigrator(ShardingConfig shardingConfig) {
        var primary = shardingConfig.groups.getFirst().members.getFirst().primaryConfig();
        FlywayHelper.migrate(primary.jdbcUrl, primary.username, primary.password);
    }
}
