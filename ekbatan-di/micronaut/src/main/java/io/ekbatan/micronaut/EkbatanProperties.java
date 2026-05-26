package io.ekbatan.micronaut;

import io.micronaut.context.annotation.ConfigurationProperties;
import java.time.Duration;

/**
 * Typed configuration for Ekbatan. The {@code ekbatan.sharding} subtree is bound separately by
 * {@link EkbatanCoreConfiguration} via the Jackson hybrid path - Micronaut's
 * {@code @ConfigurationProperties} cannot bind the {@code configs} map of mixed builder-based
 * {@code DataSourceConfig} entries directly.
 */
@ConfigurationProperties("ekbatan")
public final class EkbatanProperties {

    /** Required by Micronaut; the container instantiates this {@code @ConfigurationProperties} class and populates it via the generated setters. */
    public EkbatanProperties() {}

    private String namespace = "default";
    private final Jobs jobs = new Jobs();
    private final LocalEventHandler localEventHandler = new LocalEventHandler();

    /** {@return logical namespace recorded on every persisted event (defaults to {@code "default"})} */
    public String getNamespace() {
        return namespace;
    }

    /**
     * Sets the namespace.
     *
     * @param namespace logical namespace recorded on every persisted event.
     */
    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    /** {@return the {@code ekbatan.jobs.*} tuning subtree for the distributed job scheduler} */
    public Jobs getJobs() {
        return jobs;
    }

    /** {@return the {@code ekbatan.local-event-handler.*} tuning subtree for in-process event dispatch} */
    public LocalEventHandler getLocalEventHandler() {
        return localEventHandler;
    }

    /** Tuning knobs for the distributed job scheduler (db-scheduler under the hood). */
    @ConfigurationProperties("jobs")
    public static final class Jobs {

        /** Required by Micronaut; the container instantiates this nested {@code @ConfigurationProperties} class. */
        public Jobs() {}

        private Duration pollingInterval;
        private Duration heartbeatInterval;
        private Duration shutdownMaxWait;

        /** {@return how often the scheduler polls for due jobs; framework default if null} */
        public Duration getPollingInterval() {
            return pollingInterval;
        }

        /**
         * Sets the polling interval.
         *
         * @param pollingInterval how often the scheduler polls for due jobs.
         */
        public void setPollingInterval(Duration pollingInterval) {
            this.pollingInterval = pollingInterval;
        }

        /** {@return heartbeat interval each scheduler instance writes for liveness; framework default if null} */
        public Duration getHeartbeatInterval() {
            return heartbeatInterval;
        }

        /**
         * Sets the heartbeat interval.
         *
         * @param heartbeatInterval heartbeat written for liveness detection.
         */
        public void setHeartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
        }

        /** {@return max time to wait for in-flight jobs at shutdown; framework default if null} */
        public Duration getShutdownMaxWait() {
            return shutdownMaxWait;
        }

        /**
         * Sets the shutdown grace period.
         *
         * @param shutdownMaxWait max time to wait for in-flight jobs at shutdown.
         */
        public void setShutdownMaxWait(Duration shutdownMaxWait) {
            this.shutdownMaxWait = shutdownMaxWait;
        }
    }

    /** Tuning knobs for the in-process event handler dispatch (fanout + handling jobs). */
    @ConfigurationProperties("local-event-handler")
    public static final class LocalEventHandler {

        /** Required by Micronaut; the container instantiates this nested {@code @ConfigurationProperties} class. */
        public LocalEventHandler() {}

        private Duration fanoutPollDelay;
        private Integer fanoutBatchSize;
        private Duration handlingPollDelay;
        private Integer handlingBatchSize;
        private Duration handlingMaxBackoffCap;
        private Duration handlingRetentionWindow;
        private final Handling handling = new Handling();

        /** {@return delay between fanout job polls; framework default if null} */
        public Duration getFanoutPollDelay() {
            return fanoutPollDelay;
        }

        /**
         * Sets the fanout poll delay.
         *
         * @param fanoutPollDelay delay between fanout job polls.
         */
        public void setFanoutPollDelay(Duration fanoutPollDelay) {
            this.fanoutPollDelay = fanoutPollDelay;
        }

        /** {@return batch size for fanout-job event reads; framework default if null} */
        public Integer getFanoutBatchSize() {
            return fanoutBatchSize;
        }

        /**
         * Sets the fanout batch size.
         *
         * @param fanoutBatchSize batch size for fanout-job event reads.
         */
        public void setFanoutBatchSize(Integer fanoutBatchSize) {
            this.fanoutBatchSize = fanoutBatchSize;
        }

        /** {@return delay between handling job polls; framework default if null} */
        public Duration getHandlingPollDelay() {
            return handlingPollDelay;
        }

        /**
         * Sets the handling poll delay.
         *
         * @param handlingPollDelay delay between handling job polls.
         */
        public void setHandlingPollDelay(Duration handlingPollDelay) {
            this.handlingPollDelay = handlingPollDelay;
        }

        /** {@return batch size for handling-job notification reads; framework default if null} */
        public Integer getHandlingBatchSize() {
            return handlingBatchSize;
        }

        /**
         * Sets the handling batch size.
         *
         * @param handlingBatchSize batch size for handling-job notification reads.
         */
        public void setHandlingBatchSize(Integer handlingBatchSize) {
            this.handlingBatchSize = handlingBatchSize;
        }

        /** {@return maximum backoff cap between handler retries; framework default if null} */
        public Duration getHandlingMaxBackoffCap() {
            return handlingMaxBackoffCap;
        }

        /**
         * Sets the maximum backoff cap.
         *
         * @param handlingMaxBackoffCap maximum backoff between handler retries.
         */
        public void setHandlingMaxBackoffCap(Duration handlingMaxBackoffCap) {
            this.handlingMaxBackoffCap = handlingMaxBackoffCap;
        }

        /** {@return retention window for completed/failed notifications; framework default if null} */
        public Duration getHandlingRetentionWindow() {
            return handlingRetentionWindow;
        }

        /**
         * Sets the retention window for completed/failed notifications.
         *
         * @param handlingRetentionWindow retention window before cleanup.
         */
        public void setHandlingRetentionWindow(Duration handlingRetentionWindow) {
            this.handlingRetentionWindow = handlingRetentionWindow;
        }

        /** {@return the handling-job toggle subtree} */
        public Handling getHandling() {
            return handling;
        }

        /** Build-time toggle for the in-process handling job. */
        @ConfigurationProperties("handling")
        public static final class Handling {

            /** Required by Micronaut; the container instantiates this nested {@code @ConfigurationProperties} class. */
            public Handling() {}

            private boolean enabled = false;

            /** {@return whether the in-process handling job is enabled (defaults to {@code false})} */
            public boolean isEnabled() {
                return enabled;
            }

            /**
             * Sets whether the in-process handling job is enabled.
             *
             * @param enabled true to activate the handling job at runtime.
             */
            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }
        }
    }
}
