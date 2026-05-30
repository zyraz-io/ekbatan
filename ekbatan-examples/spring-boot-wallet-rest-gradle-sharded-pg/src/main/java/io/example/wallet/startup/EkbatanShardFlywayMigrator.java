package io.example.wallet.startup;

import io.ekbatan.core.config.ShardingConfig;
import java.util.Arrays;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Multi-shard Flyway migrator. Iterates every {@code (group, member)} pair in
 * {@link ShardingConfig} and runs the {@code classpath:db/migration/*.sql} bundle once per
 * shard's {@code primaryConfig}. Migration files are identical across shards - each shard is a
 * self-contained schema (no cross-shard FKs) and the framework writes its outbox into the
 * shard the action runs on.
 *
 * <p>This wallet doesn't use Spring Boot's {@code FlywayAutoConfiguration} (which is gated on
 * a single {@code DataSource} bean and can only attach to one shard). Auto-config is suppressed
 * via {@code spring.flyway.enabled=false} in {@code application.yml}; the
 * {@code @Bean flywayMigration} below is the marker that other beans
 * ({@code ekbatanJobRegistry} via the {@link BeanFactoryPostProcessor} edge) can
 * {@code dependsOn} so db-scheduler can't start polling {@code scheduled_tasks} until every
 * shard has its schema in place.
 *
 * <p>Mirrors the Quarkus / Micronaut / single-shard Spring Boot wallets' customizer pattern -
 * same intent (programmatic, ShardingConfig-driven), different shape because the framework
 * hook (auto-configured Flyway) doesn't extend to multi-shard scenarios.
 */
@Configuration
public class EkbatanShardFlywayMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(EkbatanShardFlywayMigrator.class);

    @Bean
    public FlywayMigration flywayMigration(ShardingConfig shardingConfig) {
        for (var group : shardingConfig.groups) {
            for (var member : group.members) {
                var primary = member.primaryConfig();
                LOG.info("Running Flyway on shard ({}, {}) -> {}", group.group, member.member, primary.jdbcUrl);
                Flyway.configure()
                        .dataSource(primary.jdbcUrl, primary.username, primary.password)
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();
            }
        }
        return new FlywayMigration();
    }

    /**
     * {@code dependsOn} edge: makes the framework-defined {@code ekbatanJobRegistry} wait until
     * {@code flywayMigration} has run on every shard - db-scheduler polls
     * {@code scheduled_tasks} from inside that registry, and the table only exists post-migration.
     *
     * <p>{@code BeanFactoryPostProcessor} (declared as a {@code static @Bean} so it runs before
     * the surrounding configuration class is instantiated) is the right hook here because
     * {@code @DependsOn} can only be placed on a bean we declare ourselves -
     * {@code ekbatanJobRegistry} is contributed by the starter, not by this project.
     */
    @Bean
    public static BeanFactoryPostProcessor jobRegistryDependsOnFlyway() {
        return beanFactory -> {
            if (!beanFactory.containsBeanDefinition("ekbatanJobRegistry")) {
                return;
            }
            var bd = beanFactory.getBeanDefinition("ekbatanJobRegistry");
            var existing = bd.getDependsOn();
            var merged = existing == null
                    ? new String[] {"flywayMigration"}
                    : Stream.concat(Arrays.stream(existing), Stream.of("flywayMigration"))
                            .toArray(String[]::new);
            bd.setDependsOn(merged);
        };
    }

    /** Marker bean. Its only purpose is to be a Spring bean other beans can {@code dependsOn}. */
    public static final class FlywayMigration {}
}
