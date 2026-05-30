package io.example.wallet;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.HashMap;
import java.util.Map;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Brings up a Testcontainers MySQL for the Quarkus integration test and publishes the
 * connection coordinates as runtime SmallRye Config properties. Two parallel coordinate
 * sets are exported:
 *
 * <ul>
 *   <li>{@code ekbatan.sharding.*} - consumed by Ekbatan's producer to build its
 *       sharding-aware {@code ConnectionProvider} used for runtime queries.</li>
 *   <li>{@code quarkus.datasource.*} - consumed by the {@code quarkus-flyway} extension
 *       (and its short-lived migration-time Hikari pool). The extension runs Flyway
 *       migrations against this datasource at app startup. Ekbatan never reads from it.</li>
 * </ul>
 *
 * <p>The two pools point at the same MySQL testcontainer; quarkus-flyway closes its
 * pool after migrations finish, leaving ekbatan's pool as the only one in use at runtime.
 *
 * <p>{@link QuarkusTestResourceLifecycleManager#start()} fires before the Quarkus app
 * context is built, so by the time {@code quarkus-flyway} runs migrations at app startup,
 * the testcontainer is up and the properties published below are visible to SmallRye Config.
 */
public class MySQLTestResource implements QuarkusTestResourceLifecycleManager {

    private final MySQLContainer container = new MySQLContainer("mysql:9.4.0")
            .withDatabaseName("wallet")
            .withUsername("wallet")
            .withPassword("wallet")
            .withEnv("TZ", "UTC")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("mysql_init.sql"), "/docker-entrypoint-initdb.d/mysql_init.sql");

    @Override
    public Map<String, String> start() {
        container.start();

        var props = new HashMap<String, String>();
        props.put("ekbatan.namespace", "test.wallet");

        // Sharding - single shard pointing at the Testcontainers MySQL.
        props.put("ekbatan.sharding.defaultShard.group", "0");
        props.put("ekbatan.sharding.defaultShard.member", "0");
        props.put("ekbatan.sharding.groups[0].group", "0");
        props.put("ekbatan.sharding.groups[0].name", "default");
        props.put("ekbatan.sharding.groups[0].members[0].member", "0");
        props.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl", container.getJdbcUrl());
        props.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username", container.getUsername());
        props.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password", container.getPassword());
        props.put(
                "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.driverClassName",
                "com.mysql.cj.jdbc.Driver");
        props.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.maximumPoolSize", "5");

        // jobsConfig - scheduler shares the same DB for this example.
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.jdbcUrl", container.getJdbcUrl());
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.username", container.getUsername());
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.password", container.getPassword());
        props.put(
                "ekbatan.sharding.groups[0].members[0].configs.jobsConfig.driverClassName", "com.mysql.cj.jdbc.Driver");
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.maximumPoolSize", "4");
        // Lock pool - backs the KeyedLockProvider.
        props.put("ekbatan.sharding.groups[0].members[0].configs.lockConfig.jdbcUrl", container.getJdbcUrl());
        props.put("ekbatan.sharding.groups[0].members[0].configs.lockConfig.username", container.getUsername());
        props.put("ekbatan.sharding.groups[0].members[0].configs.lockConfig.password", container.getPassword());
        props.put(
                "ekbatan.sharding.groups[0].members[0].configs.lockConfig.driverClassName", "com.mysql.cj.jdbc.Driver");
        props.put("ekbatan.sharding.groups[0].members[0].configs.lockConfig.maximumPoolSize", "15");
        props.put("ekbatan.sharding.groups[0].members[0].configs.lockConfig.leakDetectionThreshold", "0");

        // Tighten poll intervals so the test doesn't sit idle waiting for the listen-to-yourself
        // dispatch to drain.
        props.put("ekbatan.local-event-handler.fanout-poll-delay", "PT0.2S");
        props.put("ekbatan.local-event-handler.handling-poll-delay", "PT0.2S");
        props.put("ekbatan.jobs.polling-interval", "PT1S");
        props.put("ekbatan.jobs.shutdown-max-wait", "PT5S");

        return props;
    }

    @Override
    public void stop() {
        container.stop();
    }
}
