package io.example.wallet;

import io.ekbatan.graalvm.flyway.FlywayHelper;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.HashMap;
import java.util.Map;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Brings up a Testcontainers MariaDB for the Quarkus integration test, runs Flyway migrations,
 * then publishes the connection coordinates as runtime SmallRye Config properties so the
 * Ekbatan extension's producers see the testcontainer's URL.
 *
 * <p>{@link QuarkusTestResourceLifecycleManager#start()} fires before the Quarkus app context
 * is built, so by the time {@code FlywayConfiguration.onStartup} fires, all migrations are
 * already applied and the second Flyway invocation is a no-op (idempotent).
 */
public class MariaDBTestResource implements QuarkusTestResourceLifecycleManager {

    private final MariaDBContainer container = new MariaDBContainer("mariadb:11.8")
            .withDatabaseName("wallet")
            .withUsername("wallet")
            .withPassword("wallet")
            .withEnv("TZ", "UTC")
            // Grants the 'wallet' user cross-database privileges so the first Flyway migration
            // can CREATE DATABASE eventlog and write tables into it. Runs as root before the
            // container becomes ready.
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("mariadb_init.sql"),
                    "/docker-entrypoint-initdb.d/mariadb_init.sql");

    @Override
    public Map<String, String> start() {
        container.start();

        FlywayHelper.migrate(container.getJdbcUrl(), container.getUsername(), container.getPassword());

        var props = new HashMap<String, String>();
        props.put("ekbatan.namespace", "test.wallet");

        // Sharding — single shard pointing at the Testcontainers MariaDB.
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
                "org.mariadb.jdbc.Driver");
        props.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.maximumPoolSize", "5");

        // jobsConfig — scheduler shares the same DB for this example.
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.jdbcUrl", container.getJdbcUrl());
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.username", container.getUsername());
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.password", container.getPassword());
        props.put(
                "ekbatan.sharding.groups[0].members[0].configs.jobsConfig.driverClassName", "org.mariadb.jdbc.Driver");
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
