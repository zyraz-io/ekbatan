package io.ekbatan.test.distributed_jobs_pg;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;
import static io.ekbatan.distributedjobs.JobRegistry.jobRegistry;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.distributedjobs.DistributedJob;
import io.ekbatan.graalvm.flyway.FlywayHelper;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class JobRegistryIntegrationTest {

    @Container
    private static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:latest")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    private static final ConnectionProvider CONNECTION_PROVIDER;

    static {
        DB.start();

        var jdbcUrl = DB.getJdbcUrl();
        var username = DB.getUsername();
        var password = DB.getPassword();

        CONNECTION_PROVIDER = hikariConnectionProvider(dataSourceConfig()
                .jdbcUrl(jdbcUrl)
                .username(username)
                .password(password)
                .maximumPoolSize(8)
                .build());

        FlywayHelper.migrate(jdbcUrl, username, password);
    }

    @AfterEach
    void cleanScheduledTasks() throws Exception {
        try (var conn = CONNECTION_PROVIDER.acquire();
                var stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE scheduled_tasks");
        }
    }

    @Test
    void singleInstance_runsRegisteredJobOnSchedule() throws Exception {
        var counter = new AtomicInteger();
        var jobName = "single-" + UUID.randomUUID();

        var registry = jobRegistry()
                .connectionProvider(CONNECTION_PROVIDER)
                .withJob(new CountingJob(jobName, FixedDelay.ofMillis(200), counter))
                .pollInterval(Duration.ofMillis(50))
                .heartbeatInterval(Duration.ofSeconds(1))
                .shutdownMaxWait(Duration.ofSeconds(2))
                .registerShutdownHook(false)
                .build();

        try {
            registry.start();
            Thread.sleep(2000);
        } finally {
            registry.stop();
        }

        // 2s of runtime with 200ms FixedDelay → expect ~10 executions; allow for poll/startup lag.
        assertThat(counter.get()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void multipleInstances_runJobOncePerSlot() throws Exception {
        var counter = new AtomicInteger();
        var jobName = "shared-" + UUID.randomUUID();
        var schedule = FixedDelay.ofMillis(200);

        var registry1 = jobRegistry()
                .connectionProvider(CONNECTION_PROVIDER)
                .withJob(new CountingJob(jobName, schedule, counter))
                .pollInterval(Duration.ofMillis(50))
                .heartbeatInterval(Duration.ofSeconds(1))
                .shutdownMaxWait(Duration.ofSeconds(2))
                .registerShutdownHook(false)
                .build();
        var registry2 = jobRegistry()
                .connectionProvider(CONNECTION_PROVIDER)
                .withJob(new CountingJob(jobName, schedule, counter))
                .pollInterval(Duration.ofMillis(50))
                .heartbeatInterval(Duration.ofSeconds(1))
                .shutdownMaxWait(Duration.ofSeconds(2))
                .registerShutdownHook(false)
                .build();

        try {
            registry1.start();
            registry2.start();
            Thread.sleep(2000);
        } finally {
            registry1.stop();
            registry2.stop();
        }

        // 2s of runtime with 200ms FixedDelay → ~10 executions if coordinated, ~20 if not.
        // The atomic-claim guarantee means both instances NEVER run the same slot, so the
        // total count is bounded by what one instance would do, plus a small slack for races.
        assertThat(counter.get()).isGreaterThanOrEqualTo(5);
        assertThat(counter.get()).isLessThanOrEqualTo(15);
    }

    @Test
    void customizeScheduler_runsLastAndCanOverrideEkbatanDefaults() throws Exception {
        var customizerCalled = new AtomicBoolean(false);
        var counter = new AtomicInteger();
        var jobName = "customize-" + UUID.randomUUID();

        // pollInterval(10s) is intentionally too slow to see meaningful executions in a 2s test
        // window — without the customizer override, we'd see at most 1 execution.
        var registry = jobRegistry()
                .connectionProvider(CONNECTION_PROVIDER)
                .withJob(new CountingJob(jobName, FixedDelay.ofMillis(200), counter))
                .pollInterval(Duration.ofSeconds(10))
                .heartbeatInterval(Duration.ofSeconds(1))
                .shutdownMaxWait(Duration.ofSeconds(2))
                .registerShutdownHook(false)
                .customizeScheduler(schedulerBuilder -> {
                    customizerCalled.set(true);
                    schedulerBuilder.pollingInterval(Duration.ofMillis(50)); // override our slow 10s
                })
                .build();

        try {
            registry.start();
            Thread.sleep(2000);
        } finally {
            registry.stop();
        }

        // Customizer was invoked
        assertThat(customizerCalled.get()).isTrue();
        // Override took effect: with 50ms polling we should see many executions in 2s.
        // Without the override (10s polling), we'd see at most 1.
        assertThat(counter.get()).isGreaterThanOrEqualTo(5);
    }

    private static final class CountingJob extends DistributedJob {
        private final String name;
        private final Schedule schedule;
        private final AtomicInteger counter;

        CountingJob(String name, Schedule schedule, AtomicInteger counter) {
            this.name = name;
            this.schedule = schedule;
            this.counter = counter;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Schedule schedule() {
            return schedule;
        }

        @Override
        public void execute(ExecutionContext ctx) {
            counter.incrementAndGet();
        }
    }
}
