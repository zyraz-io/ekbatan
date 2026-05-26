package io.ekbatan.distributedjobs;

import static com.github.kagkarlsson.scheduler.SchedulerBuilder.DEFAULT_POLLING_INTERVAL;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerBuilder;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import io.ekbatan.core.persistence.ConnectionProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builder-driven facade for a single db-scheduler {@link Scheduler} instance, configured with
 * a set of {@link DistributedJob}s and backed by an Ekbatan {@link ConnectionProvider}.
 *
 * <p>Each registered job is translated to a {@link RecurringTask} and started on
 * {@link #start()}. A JVM shutdown hook that calls {@link #stop()} is installed by default
 * - pass {@code false} to {@link Builder#registerShutdownHook(boolean)} to opt out (e.g.,
 * in tests or when the host application owns shutdown).
 *
 * <p>The {@code ConnectionProvider} should typically wrap a <em>dedicated</em> pool, separate
 * from your primary application pool, since db-scheduler polls continuously.
 */
public final class JobRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(JobRegistry.class);

    private final Scheduler scheduler;
    private final List<String> jobNames;

    private JobRegistry(Scheduler scheduler, List<String> jobNames) {
        this.scheduler = scheduler;
        this.jobNames = jobNames;
    }

    /** Begins polling and executing registered jobs. */
    public void start() {
        LOG.info("Starting JobRegistry with {} job(s): {}", jobNames.size(), jobNames);
        scheduler.start();
    }

    /**
     * Gracefully stops the scheduler, waiting up to the configured {@code shutdownMaxWait}
     * (default 30 min, set via {@link Builder#shutdownMaxWait(Duration)}) for in-flight
     * executions to finish before forcing termination.
     */
    public void stop() {
        LOG.info("Stopping JobRegistry");
        scheduler.stop();
        LOG.info("JobRegistry stopped");
    }

    /** {@return a fresh builder for {@link JobRegistry}} */
    public static Builder jobRegistry() {
        return new Builder();
    }

    /** Fluent builder for {@link JobRegistry}. Obtain via {@link #jobRegistry()}. */
    public static final class Builder {

        private ConnectionProvider connectionProvider;
        private final List<DistributedJob> jobs = new ArrayList<>();
        private Duration pollInterval = DEFAULT_POLLING_INTERVAL;
        private Duration heartbeatInterval = Duration.ofSeconds(10);
        private Duration shutdownMaxWait;
        private boolean registerShutdownHook = true;
        private Consumer<SchedulerBuilder> schedulerCustomizer;

        private Builder() {}

        /**
         * Sets the database connection provider that db-scheduler will poll. Required. Should
         * typically wrap a <em>dedicated</em> pool isolated from application traffic.
         *
         * @param cp the provider; caller retains ownership of the underlying pool.
         * @return this builder, for chaining.
         */
        public Builder connectionProvider(ConnectionProvider cp) {
            this.connectionProvider = cp;
            return this;
        }

        /**
         * Adds a single job to the registry. Equivalent to {@code withJobs(List.of(job))}.
         *
         * @param job the job to schedule.
         * @return this builder, for chaining.
         */
        public Builder withJob(DistributedJob job) {
            this.jobs.add(job);
            return this;
        }

        /**
         * Adds a batch of jobs to the registry. Job {@code name()}s must be unique within the
         * resulting registry - duplicates fail {@link #build()}.
         *
         * @param jobs the jobs to schedule; must not be null.
         * @return this builder, for chaining.
         */
        public Builder withJobs(Collection<? extends DistributedJob> jobs) {
            Validate.notNull(jobs, "jobs cannot be null");
            this.jobs.addAll(jobs);
            return this;
        }

        /**
         * Sets db-scheduler's polling interval. Default is db-scheduler's
         * {@code DEFAULT_POLLING_INTERVAL}.
         *
         * @param d the polling interval.
         * @return this builder, for chaining.
         */
        public Builder pollInterval(Duration d) {
            this.pollInterval = d;
            return this;
        }

        /**
         * Sets db-scheduler's heartbeat interval. Heartbeats let other instances detect a crashed
         * worker; default is 10 seconds.
         *
         * @param d the heartbeat interval.
         * @return this builder, for chaining.
         */
        public Builder heartbeatInterval(Duration d) {
            this.heartbeatInterval = d;
            return this;
        }

        /**
         * Maximum time {@link #stop()} will wait for in-flight executions to finish before
         * forcing termination. db-scheduler's default is 30 min - useful to lower in tests.
         *
         * @param d the max wait duration at shutdown.
         * @return this builder, for chaining.
         */
        public Builder shutdownMaxWait(Duration d) {
            this.shutdownMaxWait = d;
            return this;
        }

        /**
         * Whether to install a JVM shutdown hook that calls {@link #stop()}. Default true; pass
         * {@code false} when the host application (Spring / Quarkus / Micronaut) owns shutdown.
         *
         * @param enabled true to install the JVM shutdown hook.
         * @return this builder, for chaining.
         */
        public Builder registerShutdownHook(boolean enabled) {
            this.registerShutdownHook = enabled;
            return this;
        }

        /**
         * Escape hatch for advanced db-scheduler settings not exposed by this builder (e.g.
         * {@code missedHeartbeatsLimit}, {@code deleteUnresolvedAfter}, custom polling
         * strategy). Applied <em>last</em> in {@link #build()}, so anything set here wins
         * over Ekbatan's defaults. Use sparingly - overriding {@code executorService} or
         * {@code threads} defeats the framework's threading model.
         *
         * @param customizer a callback that mutates the underlying {@link SchedulerBuilder}.
         * @return this builder, for chaining.
         */
        public Builder customizeScheduler(Consumer<SchedulerBuilder> customizer) {
            this.schedulerCustomizer = customizer;
            return this;
        }

        /** {@return a configured {@link JobRegistry}; throws if required fields are unset or job names collide} */
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
                tasks.add(Tasks.recurring(job.name(), job.schedule()).execute((_, ctx) -> {
                    LOG.info("Job '{}' execution started", job.name());
                    try {
                        job.execute(ctx);
                        LOG.info("Job '{}' execution finished", job.name());
                    } catch (RuntimeException re) {
                        LOG.error(
                                "Job '{}' execution failed: {}: {}",
                                job.name(),
                                re.getClass().getSimpleName(),
                                re.getMessage(),
                                re);
                        throw re;
                    }
                }));
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

            return new JobRegistry(schedulerBuilder.build(), List.copyOf(names));
        }
    }
}
