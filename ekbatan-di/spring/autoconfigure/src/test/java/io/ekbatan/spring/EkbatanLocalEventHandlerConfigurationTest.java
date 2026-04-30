package io.ekbatan.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.events.localeventhandler.EventHandlerRegistry;
import io.ekbatan.events.localeventhandler.job.EventFanoutJob;
import io.ekbatan.events.localeventhandler.job.EventHandlingJob;
import io.ekbatan.spring.fixture.FixtureEventHandler;
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
 * Slice tests for {@link EkbatanLocalEventHandlerConfiguration}. Covers conditional wiring
 * without a real database — end-to-end verification belongs to {@code ekbatan-integration-tests}.
 *
 * <p>Each test mocks the heavy collaborators ({@link DatabaseRegistry}, {@link ActionExecutor})
 * and lets the auto-config build {@link EventHandlerRegistry}, {@link EventFanoutJob}, and
 * (when enabled) {@link EventHandlingJob}.
 *
 * <p>Disabled on native image: slice tests use Spring's {@code ApplicationContextRunner}, which
 * relies on {@code MetadataReader} reading {@code .class} files as classpath resources at runtime.
 * Native binaries don't preserve {@code .class} files (they're consumed at AOT time), so any
 * {@code AutoConfigurations.of(...).run(...)} call fails with {@code FileNotFoundException}.
 * End-to-end native coverage of these auto-configs lives in {@code ekbatan-integration-tests:di:spring-boot-starter}'s
 * full {@code @SpringBootTest}, where Spring AOT pre-evaluates the auto-config conditions at build time.
 */
@DisabledInNativeImage
class EkbatanLocalEventHandlerConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    EkbatanCoreConfiguration.class,
                    EkbatanLocalEventHandlerConfiguration.class))
            .withUserConfiguration(MocksConfig.class);

    @Test
    void shouldNotCreateEventHandlerRegistryOrJobsWhenNoEventHandlerBeansExist() {
        // No fixture base-package registration → no @EkbatanEventHandler beans discovered →
        // @ConditionalOnBean(EventHandler.class) suppresses the entire auto-config.
        contextRunner.withPropertyValues(shardingProperties()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(EventHandlerRegistry.class);
            assertThat(ctx).doesNotHaveBean(EventFanoutJob.class);
            assertThat(ctx).doesNotHaveBean(EventHandlingJob.class);
        });
    }

    @Test
    void shouldCreateRegistryAndFanoutJobButNotHandlingJobByDefault() {
        // FixtureEventHandler IS discovered → auto-config activates → registry + fanout exist.
        // Handling is opt-in, so without ekbatan.local-event-handler.handling.enabled=true, it stays off.
        // Stub fanout to bypass real DatabaseRegistry interaction (the real EventFanoutJob
        // constructor reads dialect from defaultTransactionManager); end-to-end wiring with a
        // real DB lives in ekbatan-integration-tests. The handling-job @Bean factory has a
        // @ConditionalOnProperty gate that won't fire here, so we don't stub it.
        contextRunner
                .withInitializer(ctx -> AutoConfigurationPackages.register(
                        (BeanDefinitionRegistry) ctx.getBeanFactory(),
                        FixtureEventHandler.class.getPackage().getName()))
                .withUserConfiguration(StubFanoutConfig.class)
                .withPropertyValues(shardingProperties())
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(FixtureEventHandler.class);
                    assertThat(ctx).hasSingleBean(EventHandlerRegistry.class);
                    assertThat(ctx).hasBean("ekbatanEventFanoutJob");
                    assertThat(ctx).doesNotHaveBean(EventHandlingJob.class);
                });
    }

    @Test
    void shouldCreateHandlingJobOnlyWhenExplicitlyEnabled() {
        contextRunner
                .withInitializer(ctx -> AutoConfigurationPackages.register(
                        (BeanDefinitionRegistry) ctx.getBeanFactory(),
                        FixtureEventHandler.class.getPackage().getName()))
                .withUserConfiguration(StubFanoutAndHandlingConfig.class)
                .withPropertyValues(shardingProperties())
                .withPropertyValues("ekbatan.local-event-handler.handling.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(EventHandlerRegistry.class);
                    assertThat(ctx).hasBean("ekbatanEventFanoutJob");
                    assertThat(ctx).hasBean("ekbatanEventHandlingJob");
                });
    }

    private static String[] shardingProperties() {
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
     * Stub {@link EventFanoutJob} only. End-to-end construction (which reads {@code dialect}
     * from the default transaction manager and instantiates real jOOQ-backed repos) lives in
     * {@code ekbatan-integration-tests}.
     */
    @Configuration
    static class StubFanoutConfig {
        @Bean(name = "ekbatanEventFanoutJob")
        EventFanoutJob stubEventFanoutJob() {
            return mock(EventFanoutJob.class);
        }
    }

    /** Stubs both jobs — used by the test that explicitly enables handling. */
    @Configuration
    static class StubFanoutAndHandlingConfig {
        @Bean(name = "ekbatanEventFanoutJob")
        EventFanoutJob stubEventFanoutJob() {
            return mock(EventFanoutJob.class);
        }

        @Bean(name = "ekbatanEventHandlingJob")
        EventHandlingJob stubEventHandlingJob() {
            return mock(EventHandlingJob.class);
        }
    }
}
