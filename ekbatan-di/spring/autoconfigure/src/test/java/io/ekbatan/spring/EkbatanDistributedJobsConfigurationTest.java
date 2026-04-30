package io.ekbatan.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.distributedjobs.JobRegistry;
import io.ekbatan.spring.fixture.FixtureDistributedJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Slice tests for {@link EkbatanDistributedJobsConfiguration}. End-to-end verification
 * (real PG, real scheduler polling) belongs to {@code ekbatan-integration-tests}; these tests
 * cover only the conditional wiring logic and the fail-fast behavior on missing config.
 *
 * <p>To avoid spinning up a real Hikari pool against the {@code jobsConfig} JDBC URL during
 * these slice tests, the tests that DO include a {@code jobsConfig} provide a stub
 * {@link JobRegistry} bean so the auto-config's own factory is suppressed by
 * {@code @ConditionalOnMissingBean}.
 *
 * <p>Disabled on native image: slice tests use Spring's {@code ApplicationContextRunner}, which
 * relies on {@code MetadataReader} reading {@code .class} files as classpath resources at runtime.
 * Native binaries don't preserve {@code .class} files (they're consumed at AOT time), so any
 * {@code AutoConfigurations.of(...).run(...)} call fails with {@code FileNotFoundException}.
 * End-to-end native coverage of these auto-configs lives in {@code ekbatan-integration-tests:di:spring-boot-starter}'s
 * full {@code @SpringBootTest}, where Spring AOT pre-evaluates the auto-config conditions at build time.
 */
@DisabledInNativeImage
class EkbatanDistributedJobsConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    EkbatanCoreConfiguration.class,
                    EkbatanDistributedJobsConfiguration.class))
            .withUserConfiguration(MocksConfig.class);

    @Test
    void shouldNotCreateJobRegistryWhenNoDistributedJobBeansExist() {
        // No fixture base-package registration → no @EkbatanDistributedJob beans discovered →
        // @ConditionalOnBean(DistributedJob.class) suppresses the entire auto-config.
        contextRunner.withPropertyValues(shardingPropertiesWithoutJobsConfig()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(JobRegistry.class);
        });
    }

    @Test
    void shouldFailFastWithClearMessageWhenJobsConfigIsMissing() {
        // FixtureDistributedJob IS discovered (auto-config triggers) but the user forgot to add
        // the jobsConfig DataSource entry under the default shard's member.
        contextRunner
                .withInitializer(ctx -> AutoConfigurationPackages.register(
                        (BeanDefinitionRegistry) ctx.getBeanFactory(),
                        FixtureDistributedJob.class.getPackage().getName()))
                .withPropertyValues(shardingPropertiesWithoutJobsConfig())
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("jobsConfig")
                            .hasMessageContaining("sharding.groups[0].members[0].configs.jobsConfig");
                });
    }

    @Test
    void shouldRegisterJobRegistryWhenJobAndJobsConfigBothPresent() {
        // Discovery triggers the auto-config; jobsConfig under the default shard's member lets
        // the ConnectionProvider factory succeed. Hikari's initializationFailTimeout(-1) keeps
        // the pool from attempting an actual connection at startup, so a fake JDBC URL is fine.
        // We provide a stub JobRegistry @Bean so @ConditionalOnMissingBean skips the auto-config's
        // real one — actually starting db-scheduler against a fake DB is integration-test territory.
        contextRunner
                .withInitializer(ctx -> AutoConfigurationPackages.register(
                        (BeanDefinitionRegistry) ctx.getBeanFactory(),
                        FixtureDistributedJob.class.getPackage().getName()))
                .withUserConfiguration(StubJobRegistryConfig.class)
                .withPropertyValues(shardingPropertiesWithJobsConfig())
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasBean("ekbatanJobRegistry");
                    assertThat(ctx).hasBean("ekbatanJobsConnectionProvider");
                    assertThat(ctx).hasSingleBean(FixtureDistributedJob.class);
                });
    }

    private static String[] shardingPropertiesWithoutJobsConfig() {
        return new String[] {
            "ekbatan.sharding.defaultShard.group=0",
            "ekbatan.sharding.defaultShard.member=0",
            "ekbatan.sharding.groups[0].group=0",
            "ekbatan.sharding.groups[0].name=g",
            "ekbatan.sharding.groups[0].members[0].member=0",
            "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl=jdbc:postgresql://x:5432/db",
            "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username=u",
            "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password=p"
        };
    }

    private static String[] shardingPropertiesWithJobsConfig() {
        return new String[] {
            "ekbatan.sharding.defaultShard.group=0",
            "ekbatan.sharding.defaultShard.member=0",
            "ekbatan.sharding.groups[0].group=0",
            "ekbatan.sharding.groups[0].name=g",
            "ekbatan.sharding.groups[0].members[0].member=0",
            "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl=jdbc:postgresql://x:5432/db",
            "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username=u",
            "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password=p",
            "ekbatan.sharding.groups[0].members[0].configs.jobsConfig.jdbcUrl=jdbc:postgresql://scheduler:5432/scheduler",
            "ekbatan.sharding.groups[0].members[0].configs.jobsConfig.username=scheduler_app",
            "ekbatan.sharding.groups[0].members[0].configs.jobsConfig.password=p",
            "ekbatan.sharding.groups[0].members[0].configs.jobsConfig.maximumPoolSize=4"
        };
    }

    /** Suppress the heavy beans that would need a real database. */
    @Configuration
    static class MocksConfig {
        @Bean
        DatabaseRegistry mockDatabaseRegistry() {
            return mock(DatabaseRegistry.class);
        }

        @Bean
        ActionExecutor mockActionExecutor() {
            return mock(ActionExecutor.class);
        }
    }

    /**
     * Provides a stub {@link JobRegistry} bean so the auto-config's own factory is skipped by
     * {@code @ConditionalOnMissingBean(JobRegistry.class)} — used by the "happy path" test
     * that just wants to confirm conditional wiring without spinning up real db-scheduler.
     */
    @Configuration
    static class StubJobRegistryConfig {
        @Bean(name = "ekbatanJobRegistry")
        JobRegistry stubJobRegistry() {
            return mock(JobRegistry.class);
        }
    }
}
