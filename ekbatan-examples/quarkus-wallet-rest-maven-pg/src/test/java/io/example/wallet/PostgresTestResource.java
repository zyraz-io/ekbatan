package io.example.wallet;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.HashMap;
import java.util.Map;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Starts two database containers and publishes Ekbatan sharding properties for tests. */
public class PostgresTestResource implements QuarkusTestResourceLifecycleManager {

    private final PostgreSQLContainer global = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("wallet_global")
            .withUsername("wallet")
            .withPassword("wallet")
            .withEnv("TZ", "UTC");

    private final PostgreSQLContainer mexico = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("wallet_mexico")
            .withUsername("wallet")
            .withPassword("wallet")
            .withEnv("TZ", "UTC");

    @Override
    public Map<String, String> start() {
        global.start();
        mexico.start();

        var props = new HashMap<String, String>();
        props.put("ekbatan.namespace", "test.wallet");
        props.put("ekbatan.sharding.defaultShard.group", "0");
        props.put("ekbatan.sharding.defaultShard.member", "0");
        props.put("ekbatan.sharding.groups[0].group", "0");
        props.put("ekbatan.sharding.groups[0].name", "global");
        props.put("ekbatan.sharding.groups[0].members[0].member", "0");
        props.put("ekbatan.sharding.groups[0].members[0].name", "global");
        registerShard(
                props,
                "ekbatan.sharding.groups[0].members[0]",
                global.getJdbcUrl(),
                global.getUsername(),
                global.getPassword(),
                "org.postgresql.Driver");
        props.put("ekbatan.sharding.groups[1].group", "1");
        props.put("ekbatan.sharding.groups[1].name", "mexico");
        props.put("ekbatan.sharding.groups[1].members[0].member", "0");
        props.put("ekbatan.sharding.groups[1].members[0].name", "mexico");
        registerShard(
                props,
                "ekbatan.sharding.groups[1].members[0]",
                mexico.getJdbcUrl(),
                mexico.getUsername(),
                mexico.getPassword(),
                "org.postgresql.Driver");
        props.put("ekbatan.local-event-handler.fanout-poll-delay", "PT0.2S");
        props.put("ekbatan.local-event-handler.handling-poll-delay", "PT0.2S");
        props.put("ekbatan.jobs.polling-interval", "PT1S");
        props.put("ekbatan.jobs.shutdown-max-wait", "PT5S");
        return props;
    }

    @Override
    public void stop() {
        mexico.stop();
        global.stop();
    }

    private static void registerShard(
            Map<String, String> props,
            String prefix,
            String jdbcUrl,
            String username,
            String password,
            String driverClassName) {
        addDataSource(props, prefix + ".configs.primaryConfig", jdbcUrl, username, password, driverClassName, "5");
        addDataSource(props, prefix + ".configs.jobsConfig", jdbcUrl, username, password, driverClassName, "4");
        addDataSource(props, prefix + ".configs.lockConfig", jdbcUrl, username, password, driverClassName, "15");
        props.put(prefix + ".configs.lockConfig.leakDetectionThreshold", "0");
    }

    private static void addDataSource(
            Map<String, String> props,
            String prefix,
            String jdbcUrl,
            String username,
            String password,
            String driverClassName,
            String maximumPoolSize) {
        props.put(prefix + ".jdbcUrl", jdbcUrl);
        props.put(prefix + ".username", username);
        props.put(prefix + ".password", password);
        props.put(prefix + ".driverClassName", driverClassName);
        props.put(prefix + ".maximumPoolSize", maximumPoolSize);
    }
}
