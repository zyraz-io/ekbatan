package io.ekbatan.test.di;

import io.ekbatan.graalvm.flyway.FlywayHelper;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.HashMap;
import java.util.Map;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Brings up a Testcontainers Postgres for the Quarkus integration test, runs Flyway migrations,
 * then publishes the connection coordinates as runtime SmallRye Config properties.
 *
 * <p>{@link QuarkusTestResourceLifecycleManager#start()} fires before the Quarkus app context is
 * built, so by the time the Ekbatan extension's producers run:
 *
 * <ul>
 *   <li>The container is reachable.</li>
 *   <li>The {@code public.widgets}, {@code eventlog.events}, {@code eventlog.event_notifications}
 *       and db-scheduler {@code scheduled_tasks} tables exist (so {@code JobRegistry.start()}
 *       doesn't error on missing schema).</li>
 *   <li>The {@code ekbatan.sharding.*} keys point at the real PG.</li>
 * </ul>
 *
 * <p>We co-locate the scheduler pool ({@code jobsConfig}) with the application data — same DB,
 * different Hikari pool — to mirror what the Spring Boot integration test does. A production
 * deployment would point {@code jobsConfig.jdbcUrl} at a separate scheduler database; that's a
 * more elaborate test deferred for now.
 */
public class PostgresTestResource implements QuarkusTestResourceLifecycleManager {

    @SuppressWarnings("resource")
    private final PostgreSQLContainer container = new PostgreSQLContainer("postgres:16")
            .withDatabaseName("ekbatan_test")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    @Override
    public Map<String, String> start() {
        container.start();

        FlywayHelper.migrate(container.getJdbcUrl(), container.getUsername(), container.getPassword());

        var props = new HashMap<String, String>();
        props.put("ekbatan.namespace", "test.quarkus");

        // Sharding — single shard pointing at the Testcontainers PG.
        // driverClassName is supplied because Quarkus's runtime classloader doesn't auto-discover
        // JDBC Driver SPIs during the Arc producer phase the way a vanilla JVM does. Setting it
        // here makes Hikari explicitly Class.forName(...) the driver instead of leaning on
        // DriverManager's SPI walk.
        props.put("ekbatan.sharding.defaultShard.group", "0");
        props.put("ekbatan.sharding.defaultShard.member", "0");
        props.put("ekbatan.sharding.groups[0].group", "0");
        props.put("ekbatan.sharding.groups[0].name", "default");
        props.put("ekbatan.sharding.groups[0].members[0].member", "0");
        props.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl", container.getJdbcUrl());
        props.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username", container.getUsername());
        props.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password", container.getPassword());
        props.put(
                "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.driverClassName", "org.postgresql.Driver");
        props.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.maximumPoolSize", "5");

        // jobsConfig — scheduler shares the same PG for this smoke test.
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.jdbcUrl", container.getJdbcUrl());
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.username", container.getUsername());
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.password", container.getPassword());
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.driverClassName", "org.postgresql.Driver");
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.maximumPoolSize", "4");

        // Tighten poll intervals so the test doesn't sit idle.
        props.put("ekbatan.local-event-handler.fanout-poll-delay", "200ms");
        props.put("ekbatan.local-event-handler.handling-poll-delay", "200ms");
        props.put("ekbatan.jobs.polling-interval", "1s");
        props.put("ekbatan.jobs.shutdown-max-wait", "5s");

        return props;
    }

    @Override
    public void stop() {
        container.stop();
    }
}
