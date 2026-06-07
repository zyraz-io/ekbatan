package io.example.wallet.startup;

import io.ekbatan.core.config.ShardingConfig;
import io.ekbatan.flyway.FlywayMigrator;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs Flyway migrations against every primary shard declared under ekbatan.sharding.*. */
@ApplicationScoped
public class EkbatanShardFlywayMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(EkbatanShardFlywayMigrator.class);

    private final ShardingConfig shardingConfig;

    public EkbatanShardFlywayMigrator(ShardingConfig shardingConfig) {
        this.shardingConfig = shardingConfig;
    }

    void migrate(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent event) {
        for (var result : FlywayMigrator.migrate(shardingConfig)) {
            var target = result.target();
            LOG.info(
                    "Flyway completed on shard ({}, {}) -> {}",
                    target.shard().group,
                    target.shard().member,
                    target.dataSourceConfig().jdbcUrl);
        }
    }
}
