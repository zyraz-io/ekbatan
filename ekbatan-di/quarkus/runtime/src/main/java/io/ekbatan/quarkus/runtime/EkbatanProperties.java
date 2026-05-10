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

    /**
     * Logical namespace recorded on every persisted event. Lets multiple deployments share an
     * eventlog table without their consumers stepping on each other's events.
     *
     * @return the configured namespace, or {@code "default"} if unset.
     */
    @WithDefault("default")
    String namespace();

    /** {@return the {@code ekbatan.jobs.*} tuning subtree for the distributed job scheduler} */
    JobsConfig jobs();

    /** {@return the {@code ekbatan.local-event-handler.*} tuning subtree for in-process event dispatch} */
    @WithName("local-event-handler")
    LocalEventHandlerConfig localEventHandler();

    /** Tuning knobs for the distributed job scheduler (db-scheduler under the hood). */
    interface JobsConfig {
        /** {@return how often the scheduler polls for due jobs; framework default if absent} */
        Optional<Duration> pollingInterval();

        /** {@return heartbeat interval written by each scheduler instance for liveness; framework default if absent} */
        Optional<Duration> heartbeatInterval();

        /** {@return max time to wait for in-flight jobs to drain at shutdown; framework default if absent} */
        Optional<Duration> shutdownMaxWait();
    }

    /** Tuning knobs for the in-process event handler dispatch (fanout + handling jobs). */
    interface LocalEventHandlerConfig {
        /** {@return delay between fanout job polls; framework default if absent} */
        Optional<Duration> fanoutPollDelay();

        /** {@return batch size for fanout-job event reads; framework default if absent} */
        Optional<Integer> fanoutBatchSize();

        /** {@return delay between handling job polls; framework default if absent} */
        Optional<Duration> handlingPollDelay();

        /** {@return batch size for handling-job notification reads; framework default if absent} */
        Optional<Integer> handlingBatchSize();

        /** {@return maximum backoff cap between handler retries; framework default if absent} */
        Optional<Duration> handlingMaxBackoffCap();

        /** {@return retention window for completed/failed notifications before cleanup; framework default if absent} */
        Optional<Duration> handlingRetentionWindow();

        /** {@return the handling-job toggle subtree} */
        HandlingConfig handling();

        /**
         * Build-time toggle for the handling job — see
         * {@link EkbatanLocalEventHandlerConfiguration#ekbatanEventHandlingJob}.
         */
        interface HandlingConfig {
            /** {@return whether the in-process handling job is enabled; defaults to {@code false}} */
            @WithDefault("false")
            boolean enabled();
        }
    }
}
