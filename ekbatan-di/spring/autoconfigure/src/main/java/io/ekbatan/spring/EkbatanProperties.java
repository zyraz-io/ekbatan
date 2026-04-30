package io.ekbatan.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Flat configuration for Ekbatan. The {@code sharding} subtree is bound separately by
 * {@code EkbatanCoreConfiguration} via the Jackson hybrid path — it does not map to a
 * record component here.
 */
@ConfigurationProperties(prefix = "ekbatan")
public record EkbatanProperties(
        @DefaultValue("default") String namespace,
        @DefaultValue JobsProps jobs,
        @DefaultValue LocalEventHandlerProps localEventHandler) {

    public record JobsProps(Duration pollingInterval, Duration heartbeatInterval, Duration shutdownMaxWait) {}

    // Plain nullable types (rather than Optional<T>) because Spring's @DefaultValue on a nested
    // record doesn't recursively initialize Optional<T> components to Optional.empty() — they
    // remain null and NPE on .ifPresent(...).
    public record LocalEventHandlerProps(
            Duration fanoutPollDelay,
            Integer fanoutBatchSize,
            Duration handlingPollDelay,
            Integer handlingBatchSize,
            Duration handlingMaxBackoffCap,
            Duration handlingRetentionWindow) {}
}
