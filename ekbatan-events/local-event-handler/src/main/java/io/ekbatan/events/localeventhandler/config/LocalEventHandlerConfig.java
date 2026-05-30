package io.ekbatan.events.localeventhandler.config;

import java.time.Duration;
import java.util.Optional;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

/**
 * Tuning configuration for the in-process event handler dispatch (fanout + handling jobs). Bound
 * directly from {@code ekbatan.local-event-handler.*} keys in {@code application.yml} /
 * {@code application.properties} via the Jackson hybrid path — each DI's
 * {@code EkbatanCoreConfiguration} feeds the flat property map to {@code JavaPropsMapper} which
 * materializes this builder.
 *
 * <p>Every duration / batch-size knob is {@link Optional} so callers can set only the subset they
 * care about and leave the rest to the framework default at job-builder-apply time. The nested
 * {@link HandlingConfig#enabled} flag defaults to {@code false} - opt-in only.
 */
@JsonDeserialize(builder = LocalEventHandlerConfig.Builder.class)
public final class LocalEventHandlerConfig {

    /** Delay between fanout job polls; framework default if empty. */
    public final Optional<Duration> fanoutPollDelay;

    /** Batch size for fanout-job event reads; framework default if empty. */
    public final Optional<Integer> fanoutBatchSize;

    /** Delay between handling job polls; framework default if empty. */
    public final Optional<Duration> handlingPollDelay;

    /** Batch size for handling-job notification reads; framework default if empty. */
    public final Optional<Integer> handlingBatchSize;

    /** Maximum backoff cap between handler retries; framework default if empty. */
    public final Optional<Duration> handlingMaxBackoffCap;

    /** Retention window for completed/failed notifications before cleanup; framework default if empty. */
    public final Optional<Duration> handlingRetentionWindow;

    /** Toggle subtree for the in-process handling job. */
    public final HandlingConfig handling;

    private LocalEventHandlerConfig(Builder builder) {
        this.fanoutPollDelay = builder.fanoutPollDelay;
        this.fanoutBatchSize = builder.fanoutBatchSize;
        this.handlingPollDelay = builder.handlingPollDelay;
        this.handlingBatchSize = builder.handlingBatchSize;
        this.handlingMaxBackoffCap = builder.handlingMaxBackoffCap;
        this.handlingRetentionWindow = builder.handlingRetentionWindow;
        this.handling = builder.handling;
    }

    /** {@return a {@link LocalEventHandlerConfig} with no overrides — every knob falls through to framework defaults} */
    public static LocalEventHandlerConfig defaults() {
        return localEventHandlerConfig().build();
    }

    /** {@return a fresh builder for {@link LocalEventHandlerConfig}} */
    public static Builder localEventHandlerConfig() {
        return new Builder();
    }

    /** Fluent builder for {@link LocalEventHandlerConfig}. Obtain via {@link #localEventHandlerConfig()}. */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {

        private Optional<Duration> fanoutPollDelay = Optional.empty();
        private Optional<Integer> fanoutBatchSize = Optional.empty();
        private Optional<Duration> handlingPollDelay = Optional.empty();
        private Optional<Integer> handlingBatchSize = Optional.empty();
        private Optional<Duration> handlingMaxBackoffCap = Optional.empty();
        private Optional<Duration> handlingRetentionWindow = Optional.empty();
        private HandlingConfig handling = HandlingConfig.defaults();

        private Builder() {}

        /**
         * Overrides the fanout poll delay.
         *
         * @param fanoutPollDelay delay between fanout job polls.
         * @return this builder, for chaining.
         */
        public Builder fanoutPollDelay(Duration fanoutPollDelay) {
            this.fanoutPollDelay = Optional.ofNullable(fanoutPollDelay);
            return this;
        }

        /**
         * Overrides the fanout batch size.
         *
         * @param fanoutBatchSize batch size for fanout-job event reads.
         * @return this builder, for chaining.
         */
        public Builder fanoutBatchSize(Integer fanoutBatchSize) {
            this.fanoutBatchSize = Optional.ofNullable(fanoutBatchSize);
            return this;
        }

        /**
         * Overrides the handling poll delay.
         *
         * @param handlingPollDelay delay between handling job polls.
         * @return this builder, for chaining.
         */
        public Builder handlingPollDelay(Duration handlingPollDelay) {
            this.handlingPollDelay = Optional.ofNullable(handlingPollDelay);
            return this;
        }

        /**
         * Overrides the handling batch size.
         *
         * @param handlingBatchSize batch size for handling-job notification reads.
         * @return this builder, for chaining.
         */
        public Builder handlingBatchSize(Integer handlingBatchSize) {
            this.handlingBatchSize = Optional.ofNullable(handlingBatchSize);
            return this;
        }

        /**
         * Overrides the maximum backoff cap.
         *
         * @param handlingMaxBackoffCap maximum backoff between handler retries.
         * @return this builder, for chaining.
         */
        public Builder handlingMaxBackoffCap(Duration handlingMaxBackoffCap) {
            this.handlingMaxBackoffCap = Optional.ofNullable(handlingMaxBackoffCap);
            return this;
        }

        /**
         * Overrides the retention window.
         *
         * @param handlingRetentionWindow retention window for completed/failed notifications.
         * @return this builder, for chaining.
         */
        public Builder handlingRetentionWindow(Duration handlingRetentionWindow) {
            this.handlingRetentionWindow = Optional.ofNullable(handlingRetentionWindow);
            return this;
        }

        /**
         * Replaces the handling subtree.
         *
         * @param handling the new handling configuration.
         * @return this builder, for chaining.
         */
        public Builder handling(HandlingConfig handling) {
            this.handling = handling != null ? handling : HandlingConfig.defaults();
            return this;
        }

        /** {@return a configured {@link LocalEventHandlerConfig}} */
        public LocalEventHandlerConfig build() {
            return new LocalEventHandlerConfig(this);
        }
    }

    /**
     * Build-time toggle for the in-process handling job. Default {@code false} — opt-in only,
     * since deployments that drain notifications via an external pipeline (e.g. Kafka) keep their
     * {@code @EkbatanEventHandler} beans for serialization purposes without booting an in-process
     * consumer.
     */
    @JsonDeserialize(builder = HandlingConfig.Builder.class)
    public static final class HandlingConfig {

        /** Whether the in-process handling job is enabled at runtime. Defaults to {@code false}. */
        public final boolean enabled;

        private HandlingConfig(Builder builder) {
            this.enabled = builder.enabled;
        }

        /** {@return a {@link HandlingConfig} with {@code enabled=false}} */
        public static HandlingConfig defaults() {
            return handlingConfig().build();
        }

        /** {@return a fresh builder for {@link HandlingConfig}} */
        public static Builder handlingConfig() {
            return new Builder();
        }

        /** Fluent builder for {@link HandlingConfig}. Obtain via {@link #handlingConfig()}. */
        @JsonPOJOBuilder(withPrefix = "")
        public static final class Builder {

            private boolean enabled = false;

            private Builder() {}

            /**
             * Toggles the in-process handling job.
             *
             * @param enabled {@code true} to activate the handling job at runtime.
             * @return this builder, for chaining.
             */
            public Builder enabled(boolean enabled) {
                this.enabled = enabled;
                return this;
            }

            /** {@return a configured {@link HandlingConfig}} */
            public HandlingConfig build() {
                return new HandlingConfig(this);
            }
        }
    }
}
