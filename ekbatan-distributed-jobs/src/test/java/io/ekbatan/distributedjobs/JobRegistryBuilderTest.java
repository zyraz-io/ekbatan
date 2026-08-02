package io.ekbatan.distributedjobs;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;
import static io.ekbatan.distributedjobs.JobRegistry.jobRegistry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import io.ekbatan.core.persistence.ConnectionProvider;
import org.junit.jupiter.api.Test;

class JobRegistryBuilderTest {

    @Test
    void build_throws_whenConnectionProviderIsMissing() {
        assertThatThrownBy(() -> jobRegistry().withJob(noopJob("a")).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("connectionProvider is required");
    }

    @Test
    void build_throws_whenNoJobsRegistered() {
        assertThatThrownBy(() -> jobRegistry()
                        .connectionProvider(fakeConnectionProvider())
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one DistributedJob must be registered");
    }

    @Test
    void build_throws_whenJobNamesAreDuplicate() {
        assertThatThrownBy(() -> jobRegistry()
                        .connectionProvider(fakeConnectionProvider())
                        .withJob(noopJob("duplicate-name"))
                        .withJob(noopJob("duplicate-name"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DistributedJob names must be unique");
    }

    /**
     * A null schedule used to survive {@code build()} untouched. db-scheduler stores it without
     * looking, then dereferences it inside {@code start()} - where {@code executeOnStartup} catches
     * the failure, logs "Continuing." and carries on. The scheduler came up, every other job
     * registered, and this one was never written to {@code scheduled_tasks}, so nothing ever became
     * due for it and it never ran at all - while {@code start()} reported success and named it.
     */
    @Test
    void build_throws_whenAJobReturnsANullSchedule() {
        assertThatThrownBy(() -> jobRegistry()
                        .connectionProvider(fakeConnectionProvider())
                        .withJob(nullScheduleJob("daily-report"))
                        .build())
                // NullPointerException, matching Validate.notNull elsewhere in this builder - the
                // point is that it fails here, loudly, naming the job.
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("daily-report")
                .hasMessageContaining("null schedule()");
    }

    /** A well-formed job must still build - the check must not reject legitimate schedules. */
    @Test
    void build_succeeds_whenTheScheduleIsPresent() {
        var registry = jobRegistry()
                .connectionProvider(fakeConnectionProvider())
                .withJob(noopJob("daily-report"))
                .registerShutdownHook(false)
                .build();

        assertThat(registry).isNotNull();
    }

    private static DistributedJob nullScheduleJob(String name) {
        return new DistributedJob() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Schedule schedule() {
                // The realistic shape: a field the DI container has not injected yet, or a helper
                // that returns null on a parse failure instead of throwing.
                return null;
            }

            @Override
            public void execute(ExecutionContext ctx) {}
        };
    }

    private static DistributedJob noopJob(String name) {
        return new DistributedJob() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Schedule schedule() {
                return FixedDelay.ofMillis(100);
            }

            @Override
            public void execute(ExecutionContext ctx) {}
        };
    }

    /**
     * Real {@link ConnectionProvider} backed by a Hikari pool with a bogus URL - never opens a
     * connection because validation fails first and Hikari is configured with
     * {@code initializationFailTimeout = -1}.
     */
    private static ConnectionProvider fakeConnectionProvider() {
        return hikariConnectionProvider(dataSourceConfig()
                .jdbcUrl("jdbc:postgresql://nowhere:5432/db")
                .username("none")
                .password("none")
                .build());
    }
}
