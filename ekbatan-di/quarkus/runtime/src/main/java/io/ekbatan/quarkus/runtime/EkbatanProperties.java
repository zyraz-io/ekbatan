package io.ekbatan.quarkus.runtime;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.time.Duration;
import java.util.Optional;

/**
 * Flat runtime configuration for Ekbatan. The {@code ekbatan.sharding} subtree is bound separately
 * by {@link EkbatanCoreConfiguration#ekbatanShardingConfig} via the Jackson hybrid path — SmallRye
 * {@code @ConfigMapping} cannot bind the {@code configs} map of mixed builder-based
 * {@code DataSourceConfig} entries directly.
 */
@ConfigMapping(prefix = "ekbatan")
public interface EkbatanProperties {

    @WithDefault("default")
    String namespace();

    JobsConfig jobs();

    @WithName("local-event-handler")
    LocalEventHandlerConfig localEventHandler();

    interface JobsConfig {
        Optional<Duration> pollingInterval();

        Optional<Duration> heartbeatInterval();

        Optional<Duration> shutdownMaxWait();
    }

    interface LocalEventHandlerConfig {
        Optional<Duration> fanoutPollDelay();

        Optional<Integer> fanoutBatchSize();

        Optional<Duration> handlingPollDelay();

        Optional<Integer> handlingBatchSize();

        Optional<Duration> handlingMaxBackoffCap();

        Optional<Duration> handlingRetentionWindow();

        HandlingConfig handling();

        interface HandlingConfig {
            @WithDefault("false")
            boolean enabled();
        }
    }
}
