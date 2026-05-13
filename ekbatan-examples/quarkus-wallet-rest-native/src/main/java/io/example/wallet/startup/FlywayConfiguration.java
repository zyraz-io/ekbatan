package io.example.wallet.startup;

import io.ekbatan.core.shard.config.ShardingConfig;
import io.ekbatan.graalvm.flyway.FlywayHelper;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import org.jboss.logging.Logger;

/**
 * Runs Flyway migrations against the same MariaDB Ekbatan reads from {@code ekbatan.sharding.*}.
 * Mirrors the Spring Boot wallet example's {@code FlywayConfiguration}, adapted for Quarkus
 * CDI:
 *
 * <ul>
 *   <li>{@code @Observes StartupEvent} runs at app startup just like Spring's {@code @Bean}
 *       factory method would run during context refresh.</li>
 *   <li>{@link Priority @Priority(LIBRARY_BEFORE)} = 1000 — lower than the framework's
 *       {@code EkbatanDistributedJobsConfiguration#onStartup} (default
 *       {@link Interceptor.Priority#APPLICATION} = 2000), so this fires first and the
 *       {@code scheduled_tasks} table exists before db-scheduler polls.</li>
 * </ul>
 *
 * <p>Under {@code @QuarkusTest}, {@code MariaDBTestResource} runs Flyway separately before the
 * Quarkus app context is even built. This bean then runs Flyway again — that's fine because
 * Flyway is idempotent (it sees the schema_history rows and skips).
 */
@ApplicationScoped
public class FlywayConfiguration {

    private static final Logger LOG = Logger.getLogger(FlywayConfiguration.class);

    void onStartup(
            @Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) io.quarkus.runtime.StartupEvent ev,
            ShardingConfig shardingConfig) {
        var primary = shardingConfig.groups.getFirst().members.getFirst().primaryConfig();
        LOG.infof("Running Flyway migrations against %s", primary.jdbcUrl);
        // FlywayHelper (from ekbatan-native) installs a substrate-VM-aware resource provider
        // when running inside a native image — raw `Flyway.configure().locations("classpath:...")`
        // fails at native runtime with "Unknown prefix for location" because the substrate VM's
        // filesystem can't walk classpath: URLs the way the JVM can.
        FlywayHelper.migrate(primary.jdbcUrl, primary.username, primary.password);
    }
}
