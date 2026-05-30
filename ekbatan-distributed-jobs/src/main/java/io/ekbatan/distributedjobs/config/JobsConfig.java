package io.ekbatan.distributedjobs.config;

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
 * <p>Every knob is {@link Optional} -- an absent value falls through to db-scheduler's framework
 * default at {@code JobRegistry.Builder}-apply time, so callers can set only the subset they care
 * about and leave the rest to the framework.
 */
@JsonDeserialize(builder = JobsConfig.Builder.class)
public final class JobsConfig {

    /** How often the scheduler polls for due jobs; framework default if empty. */
    public final Optional<Duration> pollingInterval;

    /** Heartbeat interval written by each scheduler instance for liveness; framework default if empty. */
    public final Optional<Duration> heartbeatInterval;

    /** Max time to wait for in-flight jobs to drain at shutdown; framework default if empty. */
    public final Optional<Duration> shutdownMaxWait;

    private JobsConfig(Builder builder) {
        this.pollingInterval = builder.pollingInterval;
        this.heartbeatInterval = builder.heartbeatInterval;
        this.shutdownMaxWait = builder.shutdownMaxWait;
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
