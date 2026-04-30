package io.ekbatan.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.ekbatan.bootstrap.jackson.EkbatanConfigJacksonModule;
import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.action.ActionRegistry;
import io.ekbatan.core.repository.RepositoryRegistry;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.config.ShardingConfig;
import io.ekbatan.spring.fixture.FixtureAction;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;

/**
 * Component test for the auto-config wiring up to {@link ShardingConfig} and {@link ActionExecutor}
 * — without a real database. We mock the {@link DatabaseRegistry} so {@link ActionExecutor}
 * builds without opening Hikari pools. End-to-end wiring with a real Postgres lives in
 * {@code ekbatan-integration-tests}.
 *
 * <p>Disabled on native image: slice tests use Spring's {@code ApplicationContextRunner}, which
 * relies on {@code MetadataReader} reading {@code .class} files as classpath resources at runtime.
 * Native binaries don't preserve {@code .class} files (they're consumed at AOT time), so any
 * {@code AutoConfigurations.of(...).run(...)} call fails with {@code FileNotFoundException}.
 * End-to-end native coverage of these auto-configs lives in {@code ekbatan-integration-tests:di:spring-boot-starter}'s
 * full {@code @SpringBootTest}, where Spring AOT pre-evaluates the auto-config conditions at build time.
 */
@DisabledInNativeImage
class EkbatanCoreConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, EkbatanCoreConfiguration.class))
            .withUserConfiguration(MockDatabaseRegistryConfig.class);

    @Test
    void shouldBuildShardingConfigBeanFromProperties() {
        contextRunner
                .withPropertyValues(
                        "ekbatan.sharding.defaultShard.group=0",
                        "ekbatan.sharding.defaultShard.member=0",
                        "ekbatan.sharding.groups[0].group=0",
                        "ekbatan.sharding.groups[0].name=global",
                        "ekbatan.sharding.groups[0].members[0].member=0",
                        "ekbatan.sharding.groups[0].members[0].name=global-eu-1",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl=jdbc:postgresql://primary:5432/db",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username=app",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password=secret",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.maximumPoolSize=20")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(EkbatanConfigJacksonModule.class);
                    assertThat(ctx).hasSingleBean(ShardingConfig.class);
                    assertThat(ctx).hasSingleBean(ActionRegistry.class);
                    assertThat(ctx).hasSingleBean(RepositoryRegistry.class);
                    assertThat(ctx).hasSingleBean(ActionExecutor.class);

                    var cfg = ctx.getBean(ShardingConfig.class);
                    assertThat(cfg.groups).hasSize(1);
                    var member = cfg.groups.get(0).members.get(0);
                    assertThat(member.primaryConfig().jdbcUrl).isEqualTo("jdbc:postgresql://primary:5432/db");
                    assertThat(member.primaryConfig().maximumPoolSize).isEqualTo(20);
                    assertThat(member.primaryConfig().dialect).isEqualTo(SQLDialect.POSTGRES);
                });
    }

    @Test
    void shouldExposeJacksonModuleForApplicationLevelMapper() {
        contextRunner
                .withPropertyValues(
                        "ekbatan.sharding.defaultShard.group=0",
                        "ekbatan.sharding.defaultShard.member=0",
                        "ekbatan.sharding.groups[0].group=0",
                        "ekbatan.sharding.groups[0].name=g",
                        "ekbatan.sharding.groups[0].members[0].member=0",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl=jdbc:postgresql://x:5432/db",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username=u",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password=p")
                .run(ctx -> {
                    // The JacksonModule bean we expose is auto-collected by Spring Boot 4's
                    // JacksonAutoConfiguration via ObjectProvider<JacksonModule> — verify our
                    // module appears among them.
                    var modules = ctx.getBeansOfType(JacksonModule.class);
                    assertThat(modules.values()).anyMatch(m -> m instanceof EkbatanConfigJacksonModule);
                });
    }

    @Test
    void actionsShouldNotBeSpringBeansAndShouldBeRegisteredAsSingletons() {
        // Actions are framework-internal: discovered via classpath scan, instantiated once at
        // startup via AutowireCapableBeanFactory.createBean(...) (which wires constructor
        // dependencies from existing singleton beans — Clock here, plus @EkbatanRepository
        // beans in real apps), and registered in the ActionRegistry. The resulting instances
        // are NOT registered as managed Spring beans; they're reachable only via ActionExecutor
        // / ActionSpec. Per-execution state lives in Action.plan() (a ScopedValue), not on the
        // instance — so a single instance is shared across all concurrent executions.
        contextRunner
                .withInitializer(ctx -> AutoConfigurationPackages.register(
                        (BeanDefinitionRegistry) ctx.getBeanFactory(),
                        FixtureAction.class.getPackage().getName()))
                .withPropertyValues(
                        "ekbatan.sharding.defaultShard.group=0",
                        "ekbatan.sharding.defaultShard.member=0",
                        "ekbatan.sharding.groups[0].group=0",
                        "ekbatan.sharding.groups[0].name=g",
                        "ekbatan.sharding.groups[0].members[0].member=0",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl=jdbc:postgresql://x:5432/db",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username=u",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password=p")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();

                    // Action class is discoverable but NOT a Spring bean.
                    assertThat(ctx).doesNotHaveBean(FixtureAction.class);

                    // It IS in the ActionRegistry — and resolves to the same singleton on every call.
                    var registry = ctx.getBean(ActionRegistry.class);
                    var first = registry.get(FixtureAction.class);
                    var second = registry.get(FixtureAction.class);
                    assertThat(first).isInstanceOf(FixtureAction.class);
                    assertThat(first).isSameAs(second);
                });
    }

    @Test
    void shouldFailFastWhenShardingPrefixIsAbsent() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ekbatan.sharding");
        });
    }

    /**
     * Suppress the auto-config's real {@link DatabaseRegistry} (which would open Hikari pools)
     * and the {@link ActionExecutor} factory (which dereferences {@code DatabaseRegistry}
     * internals during construction) by providing mocks. {@code @ConditionalOnMissingBean} on
     * the auto-config skips its own factories in favor of these. End-to-end wiring is verified
     * by the Testcontainers integration test, not here.
     */
    @Configuration
    static class MockDatabaseRegistryConfig {
        @Bean
        DatabaseRegistry mockDatabaseRegistry() {
            return mock(DatabaseRegistry.class);
        }

        @Bean
        ActionExecutor mockActionExecutor() {
            return mock(ActionExecutor.class);
        }
    }
}
