package io.ekbatan.distributedjobs;

import static com.github.kagkarlsson.scheduler.SchedulerBuilder.DEFAULT_POLLING_INTERVAL;

import com.github.kagkarlsson.scheduler.ScheduledExecutionsFilter;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerBuilder;
import com.github.kagkarlsson.scheduler.jdbc.JdbcCustomization;
import com.github.kagkarlsson.scheduler.jdbc.MariaDBJdbcCustomization;
import com.github.kagkarlsson.scheduler.jdbc.MySQL8JdbcCustomization;
import com.github.kagkarlsson.scheduler.jdbc.PostgreSqlJdbcCustomization;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import io.ekbatan.core.internal.Validate;
import io.ekbatan.core.persistence.ConnectionProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.jooq.tools.jdbc.JDBCUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builder-driven facade for a single db-scheduler {@link Scheduler} instance, configured with
 * a set of {@link DistributedJob}s and backed by an Ekbatan {@link ConnectionProvider}.
 *
 * <p>Each registered job is translated to a {@link RecurringTask} and started on
 * {@link #start()}. A JVM shutdown hook is installed by default - pass {@code false} to
 * {@link Builder#registerShutdownHook(boolean)} to opt out (e.g. in tests, or when the host
 * application owns shutdown). It is db-scheduler's own hook, which calls {@code Scheduler.stop()}
 * directly rather than this class's {@link #stop()}, so the shutdown logging here does not appear
 * on that path.
 *
 * <p>The {@code ConnectionProvider} should typically wrap a <em>dedicated</em> pool, separate
 * from your primary application pool, since db-scheduler polls continuously.
 */
public final class JobRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(JobRegistry.class);

    /**
     * The framework's heartbeat interval - deliberately ours, not db-scheduler's 5 minutes.
     *
     * <p>Named rather than inlined because the line below it reads db-scheduler's
     * {@code DEFAULT_POLLING_INTERVAL}, and a bare {@code Duration.ofSeconds(10)} beside it looked
     * like the same thing. It is not: both happen to be 10 seconds, which is coincidence.
     *
     * <p>See {@link Builder#heartbeatInterval(Duration)} for why a short interval suits this
     * framework.
     */
    static final Duration EKBATAN_HEARTBEAT_INTERVAL = Duration.ofSeconds(10);

    private final Scheduler scheduler;
    private final List<String> jobNames;

    /**
     * The subset of {@link #jobNames} that must have a row in {@code scheduled_tasks} once
     * started. Jobs whose {@code Schedule.isDisabled()} are excluded: db-scheduler deliberately
     * writes no execution for those, so their absence is correct rather than a failure.
     */
    private final List<String> namesRequiringRegistration;

    private JobRegistry(Scheduler scheduler, List<String> jobNames, List<String> namesRequiringRegistration) {
        this.scheduler = scheduler;
        this.jobNames = jobNames;
        this.namesRequiringRegistration = namesRequiringRegistration;
    }

    /** Begins polling and executing registered jobs. */
    public void start() {
        LOG.info("Starting JobRegistry with {} job(s): {}", jobNames.size(), jobNames);
        scheduler.start();
        verifyEveryJobRegistered();
    }

    /**
     * Confirms that starting actually registered the jobs, because nothing else will say otherwise.
     *
     * <p>Registering a job means writing a row to {@code scheduled_tasks}; the scheduler finds work
     * only by reading that table. db-scheduler performs those writes inside {@code start()} through
     * {@code executeOnStartup}, which catches whatever each one throws, logs it and continues - so
     * {@code scheduler.start()} returns normally whether every row was written or none were. A
     * missing table, a database that is down, or a jobs pool without write permission all end the
     * same way: the application boots, health checks pass, the scheduler polls an empty table, and
     * no job ever runs. Not late - never.
     */
    private void verifyEveryJobRegistered() {
        final List<String> missing;
        try {
            // ScheduledExecutionsFilter.all(), not the no-argument lookup: that one applies
            // .withPicked(false) and so hides an execution that is currently running. A job that
            // fired at the moment between start() and this check would otherwise look
            // unregistered and take the application's startup down with it.
            missing = namesRequiringRegistration.stream()
                    .filter(name -> scheduler
                            .getScheduledExecutionsForTask(name, Object.class, ScheduledExecutionsFilter.all())
                            .isEmpty())
                    .toList();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "JobRegistry.start() could not verify that its jobs were registered. The scheduler is running"
                            + " but may have no work: " + namesRequiringRegistration,
                    e);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("JobRegistry.start() completed but " + missing.size() + " of "
                    + namesRequiringRegistration.size() + " job(s) were not registered: " + missing
                    + ". db-scheduler logs the cause at ERROR during startup - usually a missing scheduled_tasks"
                    + " table, or a database the jobs pool cannot write to. Those jobs would never run.");
        }
    }

    /**
     * Gracefully stops the scheduler, letting in-flight executions finish before forcing
     * termination.
     *
     * <p>The ceiling is <strong>twice</strong> {@code shutdownMaxWait}, not once: db-scheduler
     * applies the same duration to both shutdown phases - waiting politely, then waiting again
     * after interrupting - and says so in its own log line, "Will wait up to 2x". On the
     * 30-minute default that is up to an hour. Set it via
     * {@link Builder#shutdownMaxWait(Duration)}.
     *
     * <p>An execution still running when that expires is abandoned, leaving its row picked with a
     * frozen heartbeat - so it is recovered later by the same dead-execution path as a crash.
     */
    public void stop() {
        LOG.info("Stopping JobRegistry");
        scheduler.stop();
        LOG.info("JobRegistry stopped");
    }

    /**
     * The db-scheduler SQL flavour for a JDBC URL, resolved without opening a connection.
     *
     * <p>Left to itself, db-scheduler decides this once inside {@code Scheduler.create(...)} by
     * reading {@code DatabaseMetaData} from a connection, and on failure logs and keeps
     * {@code DefaultJdbcCustomization} for the scheduler's lifetime. Ekbatan's pools use
     * {@code initializationFailTimeout = -1} so an application may start before its database is
     * reachable, which makes that failure ordinary. The consequence is not subtle: the generic
     * customization emits {@code OFFSET 0 ROWS FETCH FIRST n ROWS ONLY} where every dialect
     * override emits {@code LIMIT n}, and MySQL and MariaDB cannot parse the former - so a
     * scheduler that lost the race issues an invalid poll query forever while the application
     * reports healthy.
     *
     * <p>The URL is known before any I/O, so it decides instead.
     *
     * @param jdbcUrl the jobs pool's URL.
     * @return the customization to pin, or {@code null} to leave db-scheduler to detect.
     */
    private static JdbcCustomization jdbcCustomizationFor(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }
        return switch (JDBCUtils.dialect(jdbcUrl).family()) {
            case POSTGRES -> new PostgreSqlJdbcCustomization(false, false);
            case MARIADB -> new MariaDBJdbcCustomization(false);
            // MySQLJdbcCustomization and MySQL8JdbcCustomization differ only in
            // supportsGenericLockAndFetch(); both emit LIMIT, which is what decides whether the
            // poll query parses. A URL cannot reveal the server version, so this assumes 8 or
            // later - what Ekbatan's migrations and examples target. A pre-8 server needs the
            // older one, set through Builder#customizeScheduler.
            case MYSQL -> new MySQL8JdbcCustomization(false);
            // Unreachable today: ConnectionProvider's only construction path is a DataSourceConfig,
            // which rejects any URL outside these three. Kept so the switch degrades to
            // db-scheduler's own detection rather than to a wrong dialect.
            default -> null;
        };
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
        private Duration heartbeatInterval = EKBATAN_HEARTBEAT_INTERVAL;
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
            // Rejected here rather than at build(), where it surfaced as a bare NPE from
            // job.name() with no indication of which call put the null in the list. The sibling
            // withJobs already validated its argument; this one did not.
            Validate.notNull(job, "job cannot be null");
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
            // The collection was checked but not its contents, so a null element still reached
            // build() and failed there as an unattributed NPE - the same defect as withJob(null),
            // one level down. A DI container producing a null bean is the realistic way in.
            jobs.forEach(job -> Validate.notNull(job, "jobs cannot contain a null job"));
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
         * Sets the heartbeat interval. A running execution stamps {@code last_heartbeat} on this
         * cadence, which is how other instances tell it apart from a crashed one.
         *
         * <p>Defaults to {@link #EKBATAN_HEARTBEAT_INTERVAL} (10s) - <em>not</em> db-scheduler's 5
         * minutes, which is the one knob here that does not fall through to the library. It is
         * also the unit the dead-execution threshold is built from: db-scheduler declares an
         * execution dead after {@code heartbeatInterval x missedHeartbeatsLimit}, and that limit
         * defaults to 6. So the effective window is <strong>60 seconds</strong> where
         * db-scheduler's own defaults would give 30 minutes.
         *
         * <p>The short window is deliberate for this framework. {@code EventFanoutJob} and
         * {@code EventHandlingJob} are themselves {@link DistributedJob}s, so the scheduler carries
         * the in-process event pipeline - and a recurring task is a single row, so a crashed
         * instance freezes that entire schedule until the row is revived. Half an hour of stalled
         * fanout is a worse outcome than an occasional early revival. Lengthen it if your jobs run
         * long and a duplicate is costlier than a delay; the tolerated-miss count is reachable
         * through {@link #customizeScheduler} as {@code missedHeartbeatsLimit} (minimum 4).
         *
         * <p>Note the heartbeat is written by db-scheduler's housekeeper thread, not by the thread
         * running your job, so a long-running {@code execute()} is not itself at risk of being
         * declared dead.
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
         * forcing termination.
         *
         * <p>Applied to <em>each</em> of db-scheduler's two shutdown phases, so the real ceiling
         * is twice what you set. db-scheduler's default is 30 minutes, meaning up to an hour -
         * usually worth lowering in tests.
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

            // Each job is asked for its name exactly once, and that captured value is used for
            // everything afterwards: the uniqueness check, the task registration, the
            // registration check in start(), and every log line. name() is an ordinary method a
            // user writes, and nothing obliges it to answer the same way twice - a name built
            // from a date, a UUID, or a field the DI container has not injected yet will not.
            // Asking repeatedly meant the row could be written under one name while the
            // uniqueness check examined another and the logs reported a third, leaving nothing
            // that mentions the job by the name it actually has in the database.
            // Explicit rather than inferred: this list is read again seventy lines below, where
            // "what did var resolve to" is not a question a reader should have to answer.
            List<String> names = jobs.stream()
                    .map(job -> {
                        var name = job.name();
                        // The name is what would normally identify the offending job, and here it
                        // is the broken thing - so the class is what the message can point at.
                        Validate.notBlank(
                                name,
                                "DistributedJob %s returned a blank name(); the name is the row's"
                                        + " primary key in scheduled_tasks",
                                job.getClass().getName());
                        return name;
                    })
                    .toList();
            Validate.isTrue(
                    names.size() == new HashSet<>(names).size(),
                    "DistributedJob names must be unique within a JobRegistry; got: %s",
                    names);

            List<RecurringTask<Void>> tasks = new ArrayList<>();
            // A disabled schedule is deliberately never written to scheduled_tasks, so its absence
            // after start() is correct - only the rest are expected to appear.
            List<String> namesRequiringRegistration = new ArrayList<>();
            for (var i = 0; i < jobs.size(); i++) {
                final var job = jobs.get(i);
                final var name = names.get(i);
                // Asked once and checked here, because nothing downstream will. db-scheduler stores
                // the schedule without looking at it, then dereferences it during start() - and
                // Scheduler#executeOnStartup catches whatever that throws, logs one line and
                // continues. The scheduler comes up, every other job registers, and this one is
                // simply never written to scheduled_tasks: nothing ever becomes due for it, so it
                // never runs at all, while start() reports success and names it in the log.
                var schedule = job.schedule();
                Validate.notNull(schedule, "DistributedJob '%s' returned a null schedule()", name);
                if (!schedule.isDisabled()) {
                    namesRequiringRegistration.add(name);
                }
                tasks.add(Tasks.recurring(name, schedule).execute((_, ctx) -> {
                    LOG.info("Job '{}' execution started", name);
                    try {
                        job.execute(ctx);
                        LOG.info("Job '{}' execution finished", name);
                    } catch (Throwable t) {
                        // Throwable, not RuntimeException. execute() declares no checked
                        // exceptions, so the only other thing that can arrive is an Error -
                        // NoClassDefFoundError from a missing optional dependency being the
                        // realistic one - and that used to slip past this catch. db-scheduler does
                        // log it, but under its own logger: anyone filtering on io.ekbatan.* saw a
                        // job log "execution started" and then nothing at all, with no terminal
                        // line either way. Rethrown unchanged, so db-scheduler still treats it as
                        // a failure; this only ensures our own log tells the whole story.
                        //
                        // Precise rethrow makes this legal without a throws clause: the compiler
                        // narrows t to what the body can actually throw, which is unchecked only.
                        LOG.error(
                                "Job '{}' execution failed: {}: {}",
                                name,
                                t.getClass().getSimpleName(),
                                t.getMessage(),
                                t);
                        throw t;
                    }
                }));
            }

            // Same reasoning as JobsConfig: db-scheduler's Waiter skips waiting entirely when the
            // duration is not positive, so a zero interval is a tight poll loop rather than a fast
            // one. Checked here as well because a hand-wired registry never passes through
            // JobsConfig.
            Validate.isTrue(
                    pollInterval != null && !pollInterval.isZero() && !pollInterval.isNegative(),
                    "pollInterval must be positive, but was " + pollInterval);
            Validate.isTrue(
                    heartbeatInterval != null && !heartbeatInterval.isZero() && !heartbeatInterval.isNegative(),
                    "heartbeatInterval must be positive, but was " + heartbeatInterval
                            + ". db-scheduler also derives its dead-execution window from it (interval x 2).");
            Validate.isTrue(
                    shutdownMaxWait == null || !shutdownMaxWait.isNegative(),
                    "shutdownMaxWait cannot be negative, but was " + shutdownMaxWait);

            var customization = jdbcCustomizationFor(connectionProvider.jdbcUrl());

            var schedulerBuilder = Scheduler.create(connectionProvider.getDataSource())
                    .startTasks(tasks)
                    .threads(jobs.size())
                    .heartbeatInterval(heartbeatInterval)
                    .executorService(Executors.newVirtualThreadPerTaskExecutor())
                    .pollingInterval(pollInterval);

            if (customization != null) {
                schedulerBuilder.jdbcCustomization(customization);
            } else {
                LOG.warn("Could not resolve a db-scheduler jdbc-customization from the jobs pool URL; leaving"
                        + " detection to db-scheduler, which falls back to generic SQL for the scheduler's"
                        + " lifetime if the database is unreachable at startup.");
            }

            if (shutdownMaxWait != null) {
                schedulerBuilder.shutdownMaxWait(shutdownMaxWait);
            }
            if (registerShutdownHook) {
                schedulerBuilder.registerShutdownHook();
            }
            if (schedulerCustomizer != null) {
                schedulerCustomizer.accept(schedulerBuilder);
            }

            return new JobRegistry(
                    schedulerBuilder.build(), List.copyOf(names), List.copyOf(namesRequiringRegistration));
        }
    }
}
