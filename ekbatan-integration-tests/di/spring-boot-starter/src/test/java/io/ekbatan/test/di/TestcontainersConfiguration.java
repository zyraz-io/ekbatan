package io.ekbatan.test.di;

import io.ekbatan.graalvm.flyway.FlywayHelper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * AOT-friendly Testcontainers + dynamic-properties wiring for
 * {@link EkbatanSpringBootStarterIntegrationTest}.
 *
 * <p>The earlier test used a static {@code @Container} field plus
 * {@code @DynamicPropertySource}, which works on the JVM but Spring's {@code processTestAot}
 * cannot evaluate at build time (the container has not started, so {@code DB.getJdbcUrl()}
 * throws). Spring Boot 4's blessed pattern is to make both the container and the property
 * registrar Spring beans:
 * <ul>
 *   <li>{@code PostgreSQLContainer} as a {@code @Bean} — Spring manages it as part of the
 *       context lifecycle, including starting it before properties are read.</li>
 *   <li>{@link DynamicPropertyRegistrar} as a {@code @Bean} — the bean-based equivalent of
 *       {@code @DynamicPropertySource} that Spring AOT understands. The registrar's
 *       lambda runs at context refresh on both JVM and native, so the produced JDBC URL
 *       reflects whatever ephemeral port Docker assigns at THIS run, not the AOT-time
 *       run.</li>
 * </ul>
 *
 * Pulled into the test via
 * {@code @SpringBootTest(classes = {EkbatanSpringBootTestApp.class, TestcontainersConfiguration.class})}.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:16")
                .withDatabaseName("ekbatan_test")
                .withUsername("test")
                .withPassword("test")
                .withEnv("TZ", "UTC");
    }

    /**
     * Runs Flyway migration against the (now-started) container and registers the
     * container-dependent properties (jdbc url / username / password). Static properties
     * live in the test's {@code @SpringBootTest(properties = ...)} because
     * {@link DynamicPropertyRegistrar} beans run as a {@code BeanFactoryPostProcessor}
     * — i.e. AFTER {@code @ConditionalOnProperty} is evaluated. Anything the auto-config
     * gates on (e.g. {@code ekbatan.local-event-handler.handling.enabled}) must be set
     * earlier than that, hence the split.
     */
    @Bean
    DynamicPropertyRegistrar ekbatanShardingProperties(PostgreSQLContainer postgres) {
        return registry -> {
            FlywayHelper.migrate(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

            registry.add("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl", postgres::getJdbcUrl);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username", postgres::getUsername);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password", postgres::getPassword);

            registry.add("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.jdbcUrl", postgres::getJdbcUrl);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.username", postgres::getUsername);
            registry.add("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.password", postgres::getPassword);
        };
    }
}
