package io.example.wallet.startup;

import io.ekbatan.core.config.ShardingConfig;
import io.micronaut.context.annotation.Context;
import org.flywaydb.core.Flyway;

/**
 * Runs Flyway migrations at application startup using connection coordinates from
 * {@code ekbatan.sharding.*} - single source of truth, no separate {@code flyway.*}
 * YAML block, no {@code micronaut-flyway} auto-configured beans.
 *
 * <p>Marked {@code @Context} so Micronaut instantiates it eagerly during application
 * startup (before lazy {@code @Singleton} beans like Ekbatan's {@code DatabaseRegistry}),
 * guaranteeing migrations are applied before anything else touches the database.
 *
 * <p>Why not the customizer route? With the customizer, the source of truth lived in
 * two places - {@code application.yml}'s {@code flyway.datasources.default} block (with
 * ugly {@code ${...}} interpolation chasing back into {@code ekbatan.sharding.*}) plus
 * the customizer overriding it at runtime. Here we skip the YAML block entirely and
 * construct {@link Flyway} directly from the typed {@link ShardingConfig}.
 */
@Context
public class EkbatanFlywayMigrator {

    public EkbatanFlywayMigrator(ShardingConfig shardingConfig) {
        var primary = shardingConfig.groups.getFirst().members.getFirst().primaryConfig();
        Flyway.configure()
                .dataSource(primary.jdbcUrl, primary.username, primary.password)
                .load()
                .migrate();
    }
}
