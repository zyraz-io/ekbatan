package io.example.wallet;

import io.ekbatan.core.shard.config.ShardingConfig;
import io.ekbatan.graalvm.flyway.FlywayHelper;
import java.util.Arrays;
import java.util.stream.Stream;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs Flyway migrations against the same Postgres Ekbatan reads from {@code ekbatan.sharding.*}.
 * Uses {@link FlywayHelper} (from {@code ekbatan-native}) rather than {@code Flyway} directly —
 * inside a GraalVM native image, {@code FlywayHelper} installs a resource provider that walks
 * classpath migrations through the substrate-VM filesystem, which raw Flyway can't do alone.
 *
 * <p>The {@link BeanFactoryPostProcessor} mutates the framework-defined {@code ekbatanJobRegistry}
 * bean definition to add a {@code dependsOn} edge — that way db-scheduler's {@code start()} can't
 * poll {@code scheduled_tasks} before migrations have created the table.
 *
 * <p><b>AOT guard:</b> Spring AOT runs {@code Application.main(...)} at build time to generate
 * the bean factory. The {@code spring.aot.processing} system property is set during that pass,
 * and we use it to skip the actual migration call — the marker bean is still returned so the
 * {@code dependsOn} edge the BFPP wires remains satisfiable.
 */
@Configuration
public class FlywayConfiguration {

    @Bean
    public FlywayMigration flywayMigration(ShardingConfig shardingConfig) {
        if (!Boolean.getBoolean("spring.aot.processing")) {
            var primary = shardingConfig.groups.getFirst().members.getFirst().primaryConfig();
            FlywayHelper.migrate(primary.jdbcUrl, primary.username, primary.password);
        }
        return new FlywayMigration();
    }

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

    /** Marker — its only purpose is to be a Spring bean other beans can {@code dependsOn}. */
    public static final class FlywayMigration {}
}
