package io.ekbatan.micronaut;

import io.micronaut.context.annotation.ConfigurationProperties;
import java.time.Duration;

/**
 * Typed configuration for Ekbatan. The {@code ekbatan.sharding} subtree is bound separately by
 * {@link EkbatanCoreConfiguration} via the Jackson hybrid path — Micronaut's
 * {@code @ConfigurationProperties} cannot bind the {@code configs} map of mixed builder-based
 * {@code DataSourceConfig} entries directly.
 */
@ConfigurationProperties("ekbatan")
public final class EkbatanProperties {

    private String namespace = "default";
    private final Jobs jobs = new Jobs();
    private final LocalEventHandler localEventHandler = new LocalEventHandler();

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public Jobs getJobs() {
        return jobs;
    }

    public LocalEventHandler getLocalEventHandler() {
        return localEventHandler;
    }

    @ConfigurationProperties("jobs")
    public static final class Jobs {
        private Duration pollingInterval;
        private Duration heartbeatInterval;
        private Duration shutdownMaxWait;

        public Duration getPollingInterval() {
            return pollingInterval;
        }

        public void setPollingInterval(Duration pollingInterval) {
            this.pollingInterval = pollingInterval;
        }

        public Duration getHeartbeatInterval() {
            return heartbeatInterval;
        }

        public void setHeartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
        }

        public Duration getShutdownMaxWait() {
            return shutdownMaxWait;
        }

        public void setShutdownMaxWait(Duration shutdownMaxWait) {
            this.shutdownMaxWait = shutdownMaxWait;
        }
    }

    @ConfigurationProperties("local-event-handler")
    public static final class LocalEventHandler {
        private Duration fanoutPollDelay;
        private Integer fanoutBatchSize;
        private Duration handlingPollDelay;
        private Integer handlingBatchSize;
        private Duration handlingMaxBackoffCap;
        private Duration handlingRetentionWindow;
        private final Handling handling = new Handling();

        public Duration getFanoutPollDelay() {
            return fanoutPollDelay;
        }

        public void setFanoutPollDelay(Duration fanoutPollDelay) {
            this.fanoutPollDelay = fanoutPollDelay;
        }

        public Integer getFanoutBatchSize() {
            return fanoutBatchSize;
        }

        public void setFanoutBatchSize(Integer fanoutBatchSize) {
            this.fanoutBatchSize = fanoutBatchSize;
        }

        public Duration getHandlingPollDelay() {
            return handlingPollDelay;
        }

        public void setHandlingPollDelay(Duration handlingPollDelay) {
            this.handlingPollDelay = handlingPollDelay;
        }

        public Integer getHandlingBatchSize() {
            return handlingBatchSize;
        }

        public void setHandlingBatchSize(Integer handlingBatchSize) {
            this.handlingBatchSize = handlingBatchSize;
        }

        public Duration getHandlingMaxBackoffCap() {
            return handlingMaxBackoffCap;
        }

        public void setHandlingMaxBackoffCap(Duration handlingMaxBackoffCap) {
            this.handlingMaxBackoffCap = handlingMaxBackoffCap;
        }

        public Duration getHandlingRetentionWindow() {
            return handlingRetentionWindow;
        }

        public void setHandlingRetentionWindow(Duration handlingRetentionWindow) {
            this.handlingRetentionWindow = handlingRetentionWindow;
        }

        public Handling getHandling() {
            return handling;
        }

        @ConfigurationProperties("handling")
        public static final class Handling {
            private boolean enabled = false;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }
        }
    }
}
