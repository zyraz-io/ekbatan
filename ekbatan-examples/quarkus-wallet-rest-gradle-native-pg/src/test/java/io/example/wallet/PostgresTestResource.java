package io.example.wallet;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.HashMap;
import java.util.Map;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Brings up a Testcontainers Postgres for the Quarkus integration test and publishes the
 * connection coordinates as runtime SmallRye Config properties. Two parallel coordinate
 * sets are exported:
 *
 * <ul>
 *   <li>{@code ekbatan.sharding.*} — consumed by Ekbatan's producer to build its
 *       sharding-aware {@code ConnectionProvider} used for runtime queries.</li>
 *   <li>{@code quarkus.datasource.*} — consumed by the {@code quarkus-flyway} extension
 *       (and its short-lived migration-time Hikari pool). The extension runs Flyway
 *       migrations against this datasource at app startup. Ekbatan never reads from it.</li>
 * </ul>
 *
 * <p>The two pools point at the same Postgres testcontainer; quarkus-flyway closes its
 * pool after migrations finish, leaving ekbatan's pool as the only one in use at runtime.
 *
 * <p>{@link QuarkusTestResourceLifecycleManager#start()} fires before the Quarkus app
 * context is built, so by the time {@code quarkus-flyway} runs migrations at app startup,
 * the testcontainer is up and the properties published below are visible to SmallRye Config.
 */
public class PostgresTestResource implements QuarkusTestResourceLifecycleManager {

    private final PostgreSQLContainer container = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("wallet")
            .withUsername("wallet")
            .withPassword("wallet")
            .withEnv("TZ", "UTC");

    @Override
    public Map<String, String> start() {
        container.start();

        var props = new HashMap<String, String>();
        props.put("ekbatan.namespace", "test.wallet");

        // Sharding — single shard pointing at the Testcontainers Postgres.
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

        // jobsConfig — scheduler shares the same DB for this example.
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.jdbcUrl", container.getJdbcUrl());
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.username", container.getUsername());
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.password", container.getPassword());
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.driverClassName", "org.postgresql.Driver");
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.maximumPoolSize", "4");

        // Tighten poll intervals so the test doesn't sit idle waiting for the listen-to-yourself
        // dispatch to drain.
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
