package io.example.wallet.startup;

import io.ekbatan.core.config.ShardingConfig;
import io.quarkus.flyway.FlywayConfigurationCustomizer;
import jakarta.enterprise.context.ApplicationScoped;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * Points {@code quarkus-flyway} at the default shard's {@code primaryConfig} so there's a
 * single source of truth for connection coordinates: {@code ekbatan.sharding.*}. Without
 * this customizer, the {@code quarkus-flyway} extension would migrate against whatever
 * {@code quarkus.datasource.{url, username, password}} is set to, requiring those keys
 * to be declared in {@code application.properties} (or derived via SmallRye expressions)
 * - both approaches duplicate config we already publish under {@code ekbatan.sharding.*}.
 *
 * <p>Quarkus discovers any CDI bean implementing
 * {@link FlywayConfigurationCustomizer} and calls {@link #customize(FluentConfiguration)}
 * after applying the {@code quarkus.flyway.*} / {@code quarkus.datasource.*} config but
 * before building the {@code Flyway} instance. Calling
 * {@link FluentConfiguration#dataSource(String, String, String)} overrides whatever
 * datasource Quarkus prepared - so we don't actually consult {@code quarkus.datasource.*}
 * for connection details, only for the {@code db-kind} (needed so Quarkus instantiates a
 * Flyway bean for it in the first place).
 *
 * <p>Migrations run at app startup because {@code quarkus.flyway.migrate-at-start=true};
 * by the time the {@code StartupEvent} observers (and ekbatan-jobs's
 * {@code scheduled_tasks} polling) fire, the schema already exists.
 */
@ApplicationScoped
public class EkbatanShardFlywayCustomizer implements FlywayConfigurationCustomizer {

    private final ShardingConfig shardingConfig;

    public EkbatanShardFlywayCustomizer(ShardingConfig shardingConfig) {
        this.shardingConfig = shardingConfig;
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        var primary = shardingConfig.groups.getFirst().members.getFirst().primaryConfig();
        configuration.dataSource(primary.jdbcUrl, primary.username, primary.password);
    }
}
