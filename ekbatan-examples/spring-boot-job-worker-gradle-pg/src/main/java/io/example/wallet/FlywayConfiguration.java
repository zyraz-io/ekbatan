package io.example.wallet;

import io.ekbatan.core.config.ShardingConfig;
import io.ekbatan.flyway.FlywayMigrator;
import java.util.Arrays;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runs Flyway migrations against every primary shard declared under ekbatan.sharding.*. */
@Configuration
public class FlywayConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(FlywayConfiguration.class);

    @Bean
    public FlywayMigration flywayMigration(ShardingConfig shardingConfig) {
        for (var result : FlywayMigrator.migrate(shardingConfig)) {
            var target = result.target();
            LOG.info(
                    "Flyway completed on shard ({}, {}) -> {}",
                    target.shard().group,
                    target.shard().member,
                    target.dataSourceConfig().jdbcUrl);
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

    public static final class FlywayMigration {}
}
