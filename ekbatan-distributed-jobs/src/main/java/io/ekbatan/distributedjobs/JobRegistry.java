package io.ekbatan.distributedjobs;

import static com.github.kagkarlsson.scheduler.SchedulerBuilder.DEFAULT_POLLING_INTERVAL;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerBuilder;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import io.ekbatan.core.persistence.ConnectionProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.apache.commons.lang3.Validate;

/**
 * Builder-driven facade for a single db-scheduler {@link Scheduler} instance, configured with
 * a set of {@link DistributedJob}s and backed by an Ekbatan {@link ConnectionProvider}.
 *
 * <p>Each registered job is translated to a {@link RecurringTask} and started on
 * {@link #start()}. A JVM shutdown hook that calls {@link #stop()} is installed by default
 * — pass {@code false} to {@link Builder#registerShutdownHook(boolean)} to opt out (e.g.,
 * in tests or when the host application owns shutdown).
 *
 * <p>The {@code ConnectionProvider} should typically wrap a <em>dedicated</em> pool, separate
 * from your primary application pool, since db-scheduler polls continuously.
 */
public final class JobRegistry {

    private final Scheduler scheduler;

    private JobRegistry(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** Begins polling and executing registered jobs. */
    public void start() {
        scheduler.start();
    }

    /**
     * Gracefully stops the scheduler, waiting up to the configured {@code shutdownMaxWait}
     * (default 30 min, set via {@link Builder#shutdownMaxWait(Duration)}) for in-flight
     * executions to finish before forcing termination.
     */
    public void stop() {
        scheduler.stop();
    }

    public static Builder jobRegistry() {
        return new Builder();
    }

    public static final class Builder {

        private ConnectionProvider connectionProvider;
        private final List<DistributedJob> jobs = new ArrayList<>();
        private Duration pollInterval = DEFAULT_POLLING_INTERVAL;
        private Duration heartbeatInterval = Duration.ofSeconds(10);
        private Duration shutdownMaxWait;
        private boolean registerShutdownHook = true;
        private Consumer<SchedulerBuilder> schedulerCustomizer;

        private Builder() {}

        public Builder connectionProvider(ConnectionProvider cp) {
            this.connectionProvider = cp;
            return this;
        }

        public Builder withJob(DistributedJob job) {
            this.jobs.add(job);
            return this;
        }

        public Builder pollInterval(Duration d) {
            this.pollInterval = d;
            return this;
        }

        public Builder heartbeatInterval(Duration d) {
            this.heartbeatInterval = d;
            return this;
        }

        /**
         * Maximum time {@link #stop()} will wait for in-flight executions to finish before
         * forcing termination. db-scheduler's default is 30 min — useful to lower in tests.
         */
        public Builder shutdownMaxWait(Duration d) {
            this.shutdownMaxWait = d;
            return this;
        }

        public Builder registerShutdownHook(boolean enabled) {
            this.registerShutdownHook = enabled;
            return this;
        }

        /**
         * Escape hatch for advanced db-scheduler settings not exposed by this builder (e.g.
         * {@code missedHeartbeatsLimit}, {@code deleteUnresolvedAfter}, custom polling
         * strategy). Applied <em>last</em> in {@link #build()}, so anything set here wins
         * over Ekbatan's defaults. Use sparingly — overriding {@code executorService} or
         * {@code threads} defeats the framework's threading model.
         */
        public Builder customizeScheduler(Consumer<SchedulerBuilder> customizer) {
            this.schedulerCustomizer = customizer;
            return this;
        }

        public JobRegistry build() {
            Validate.notNull(connectionProvider, "connectionProvider is required");
            Validate.notEmpty(jobs, "at least one DistributedJob must be registered");

            var names = jobs.stream().map(DistributedJob::name).toList();
            Validate.isTrue(
                    names.size() == new HashSet<>(names).size(),
                    "DistributedJob names must be unique within a JobRegistry; got: %s",
                    names);

            List<RecurringTask<Void>> tasks = new ArrayList<>();
            for (var job : jobs) {
                tasks.add(Tasks.recurring(job.name(), job.schedule()).execute((_, ctx) -> job.execute(ctx)));
            }

            var schedulerBuilder = Scheduler.create(connectionProvider.getDataSource())
                    .startTasks(tasks)
                    .threads(jobs.size())
                    .heartbeatInterval(heartbeatInterval)
                    .executorService(Executors.newVirtualThreadPerTaskExecutor())
                    .pollingInterval(pollInterval);

            if (shutdownMaxWait != null) {
                schedulerBuilder.shutdownMaxWait(shutdownMaxWait);
            }
            if (registerShutdownHook) {
                schedulerBuilder.registerShutdownHook();
            }
            if (schedulerCustomizer != null) {
                schedulerCustomizer.accept(schedulerBuilder);
            }

            return new JobRegistry(schedulerBuilder.build());
        }
    }
}
