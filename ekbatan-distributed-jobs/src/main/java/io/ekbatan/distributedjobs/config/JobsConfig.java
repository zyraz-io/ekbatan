package io.ekbatan.distributedjobs.config;

import io.ekbatan.core.internal.Validate;
import java.time.Duration;
import java.util.Optional;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

/**
 * Tuning configuration for the distributed job scheduler (db-scheduler under the hood). Bound
 * directly from {@code ekbatan.jobs.*} keys in {@code application.yml}/{@code application.properties}
 * via the Jackson hybrid path -- each DI's {@code EkbatanCoreConfiguration} feeds the flat property
 * map to {@code JavaPropsMapper} which materializes this builder.
 *
 * <p>Every knob is {@link Optional} -- an absent value is simply not applied, so callers can set
 * only the subset they care about. What an absent value then resolves to is
 * {@code JobRegistry.Builder}'s default, which is <em>usually</em> but not always db-scheduler's:
 *
 * <ul>
 *   <li>{@code pollingInterval} -- db-scheduler's {@code DEFAULT_POLLING_INTERVAL} (10s).</li>
 *   <li>{@code heartbeatInterval} -- <strong>the framework's own 10s</strong>, not db-scheduler's
 *       5 minutes. Because db-scheduler declares an execution dead after
 *       {@code heartbeatInterval x missedHeartbeatsLimit} and that limit defaults to 6, the
 *       effective dead-execution window is 60 seconds rather than 30 minutes. See
 *       {@code JobRegistry.Builder#heartbeatInterval} for why.</li>
 *   <li>{@code shutdownMaxWait} -- db-scheduler's 30 minutes, applied to each of its two shutdown
 *       phases, so up to an hour in total.</li>
 * </ul>
 *
 * <p>This list used to read simply "falls through to db-scheduler's framework default", which was
 * true of two of the three and quietly wrong about the heartbeat.
 */
@JsonDeserialize(builder = JobsConfig.Builder.class)
public final class JobsConfig {

    /** How often the scheduler polls for due jobs; framework default if empty. */
    public final Optional<Duration> pollingInterval;

    /**
     * Heartbeat interval written by each running execution for liveness. Empty means the
     * framework's 10s - not db-scheduler's 5 minutes - giving a 60s dead-execution window.
     */
    public final Optional<Duration> heartbeatInterval;

    /** Max time to wait for in-flight jobs to drain at shutdown; framework default if empty. */
    public final Optional<Duration> shutdownMaxWait;

    private JobsConfig(Builder builder) {
        this.pollingInterval = requirePositive(builder.pollingInterval, "ekbatan.jobs.polling-interval");
        this.heartbeatInterval = requirePositive(builder.heartbeatInterval, "ekbatan.jobs.heartbeat-interval");
        this.shutdownMaxWait = requireNonNegative(builder.shutdownMaxWait, "ekbatan.jobs.shutdown-max-wait");
    }

    /**
     * Rejects zero and negative intervals.
     *
     * <p>db-scheduler waits between iterations with a {@code Waiter}, whose {@code doWait()} is
     * {@code if (duration.toMillis() > 0) { ...sleep... }} - so a zero or negative value does not
     * mean "wait briefly", it means <em>do not wait at all</em>, and the poll loop spins against
     * the database as fast as the CPU allows. Nothing throws and nothing is logged.
     *
     * <p>The heartbeat is worse than it looks: db-scheduler derives the dead-execution window from
     * it as {@code heartbeatInterval * 2}, so a tiny value also makes healthy executions look dead
     * and eligible for revival elsewhere.
     */
    private static Optional<Duration> requirePositive(Optional<Duration> value, String property) {
        value.ifPresent(d -> Validate.isTrue(
                !d.isZero() && !d.isNegative(),
                property + " must be positive, but was " + d + ". A zero or negative interval makes db-scheduler"
                        + " skip its wait entirely and poll in a tight loop."));
        return value;
    }

    /**
     * Rejects negative waits. Zero is allowed here and means "do not wait for in-flight jobs at
     * shutdown", which is a legitimate choice for a container that is about to be killed anyway.
     */
    private static Optional<Duration> requireNonNegative(Optional<Duration> value, String property) {
        value.ifPresent(d -> Validate.isTrue(!d.isNegative(), property + " cannot be negative, but was " + d + "."));
        return value;
    }

    /** {@return a {@link JobsConfig} with no overrides -- every knob falls through to framework defaults} */
    public static JobsConfig defaults() {
        return jobsConfig().build();
    }

    /** {@return a fresh builder for {@link JobsConfig}} */
    public static Builder jobsConfig() {
        return new Builder();
    }

    /** Fluent builder for {@link JobsConfig}. Obtain via {@link #jobsConfig()}. */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {

        private Optional<Duration> pollingInterval = Optional.empty();
        private Optional<Duration> heartbeatInterval = Optional.empty();
        private Optional<Duration> shutdownMaxWait = Optional.empty();

        private Builder() {}

        /**
         * Overrides the polling interval.
         *
         * @param pollingInterval how often the scheduler polls for due jobs.
         * @return this builder, for chaining.
         */
        public Builder pollingInterval(Duration pollingInterval) {
            this.pollingInterval = Optional.ofNullable(pollingInterval);
            return this;
        }

        /**
         * Overrides the heartbeat interval.
         *
         * @param heartbeatInterval heartbeat written for liveness detection.
         * @return this builder, for chaining.
         */
        public Builder heartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = Optional.ofNullable(heartbeatInterval);
            return this;
        }

        /**
         * Overrides the shutdown grace period.
         *
         * @param shutdownMaxWait max time to wait for in-flight jobs at shutdown.
         * @return this builder, for chaining.
         */
        public Builder shutdownMaxWait(Duration shutdownMaxWait) {
            this.shutdownMaxWait = Optional.ofNullable(shutdownMaxWait);
            return this;
        }

        /** {@return a configured {@link JobsConfig}} */
        public JobsConfig build() {
            return new JobsConfig(this);
        }
    }
}
