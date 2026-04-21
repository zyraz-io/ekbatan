package io.ekbatan.distributedjobs;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;
import static io.ekbatan.distributedjobs.JobRegistry.jobRegistry;
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
     * Real {@link ConnectionProvider} backed by a Hikari pool with a bogus URL — never opens a
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
