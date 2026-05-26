package io.ekbatan.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Flat configuration for Ekbatan. The {@code sharding} subtree is bound separately by
 * {@code EkbatanCoreConfiguration} via the Jackson hybrid path - it does not map to a
 * record component here.
 *
 * @param namespace logical namespace recorded on every persisted event ({@code "default"} if unset).
 * @param jobs tuning for the distributed job scheduler ({@code ekbatan.jobs.*}).
 * @param localEventHandler tuning for the in-process event handler dispatch ({@code ekbatan.local-event-handler.*}).
 */
@ConfigurationProperties(prefix = "ekbatan")
public record EkbatanProperties(
        @DefaultValue("default") String namespace,
        @DefaultValue JobsProps jobs,
        @DefaultValue LocalEventHandlerProps localEventHandler) {

    /**
     * Tuning knobs for the distributed job scheduler (db-scheduler under the hood).
     *
     * @param pollingInterval how often the scheduler polls for due jobs; framework default if null.
     * @param heartbeatInterval heartbeat interval written by each scheduler instance for liveness; framework default if null.
     * @param shutdownMaxWait max time to wait for in-flight jobs at shutdown; framework default if null.
     */
    public record JobsProps(Duration pollingInterval, Duration heartbeatInterval, Duration shutdownMaxWait) {}

    /**
     * Tuning knobs for the in-process event handler dispatch (fanout + handling jobs).
     *
     * <p>Plain nullable types (rather than {@code Optional<T>}) because Spring's {@code @DefaultValue}
     * on a nested record doesn't recursively initialize {@code Optional<T>} components to
     * {@code Optional.empty()} - they remain null and NPE on {@code .ifPresent(...)}.
     *
     * @param fanoutPollDelay delay between fanout job polls; framework default if null.
     * @param fanoutBatchSize batch size for fanout-job event reads; framework default if null.
     * @param handlingPollDelay delay between handling job polls; framework default if null.
     * @param handlingBatchSize batch size for handling-job notification reads; framework default if null.
     * @param handlingMaxBackoffCap maximum backoff cap between handler retries; framework default if null.
     * @param handlingRetentionWindow retention window for completed/failed notifications; framework default if null.
     */
    public record LocalEventHandlerProps(
            Duration fanoutPollDelay,
            Integer fanoutBatchSize,
            Duration handlingPollDelay,
            Integer handlingBatchSize,
            Duration handlingMaxBackoffCap,
            Duration handlingRetentionWindow) {}
}
