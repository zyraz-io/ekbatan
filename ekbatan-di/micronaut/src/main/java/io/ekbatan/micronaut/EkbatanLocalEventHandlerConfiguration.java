package io.ekbatan.micronaut;

import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.events.localeventhandler.EventHandler;
import io.ekbatan.events.localeventhandler.EventHandlerRegistry;
import io.ekbatan.events.localeventhandler.config.LocalEventHandlerConfig;
import io.ekbatan.events.localeventhandler.job.EventFanoutJob;
import io.ekbatan.events.localeventhandler.job.EventHandlingJob;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * Micronaut {@code @Factory} for Ekbatan's in-process event handler dispatch. Activates only
 * when {@code EventHandlerRegistry} is present on the classpath (the
 * {@code ekbatan-local-event-handler} dependency is in the build).
 *
 * <p>Produces an {@link EventHandlerRegistry} from the discovered {@code EventHandler} beans
 * plus an {@link EventFanoutJob} / {@link EventHandlingJob} pair that polls the outbox and
 * delivers events to handlers. Set {@code ekbatan.local-event-handler.handling.enabled=true}
 * in {@code application.yml} to activate the handling job.
 */
@Factory
@Requires(classes = EventHandlerRegistry.class)
public class EkbatanLocalEventHandlerConfiguration {

    /** Required by Micronaut; the container instantiates this {@code @Factory} class to invoke its {@code @Bean} methods. */
    public EkbatanLocalEventHandlerConfiguration() {}

    /**
     * Bundles every {@code @EkbatanEventHandler}-annotated bean discovered by Micronaut into a
     * single {@link EventHandlerRegistry} used by the fanout + handling jobs.
     *
     * @param handlers the application's event-handler beans, injected by Micronaut.
     * @return the registry consulted during fanout to materialize per-handler notification rows.
     */
    @Bean
    @Singleton
    public EventHandlerRegistry ekbatanEventHandlerRegistry(List<EventHandler<?>> handlers) {
        return EventHandlerRegistry.eventHandlerRegistry()
                .withHandlers(handlers)
                .build();
    }

    /**
     * Produces the fanout job that copies newly-committed events from the eventlog into
     * per-handler {@code event_notifications} rows. Tuning comes from
     * {@code ekbatan.local-event-handler.fanout-*}.
     *
     * @param databaseRegistry the per-shard connection pools.
     * @param handlerRegistry the registry of event handlers.
     * @param localEventHandlerConfig the parsed {@code ekbatan.local-event-handler.*} subtree.
     * @param clock the system clock used for fanout cursor timestamps.
     * @return the fanout job, scheduled by Micronaut once started.
     */
    @Bean
    @Singleton
    public EventFanoutJob ekbatanEventFanoutJob(
            DatabaseRegistry databaseRegistry,
            EventHandlerRegistry handlerRegistry,
            LocalEventHandlerConfig localEventHandlerConfig,
            Clock clock) {
        var builder = EventFanoutJob.eventFanoutJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(handlerRegistry)
                .clock(clock);
        localEventHandlerConfig.fanoutPollDelay.ifPresent(builder::pollDelay);
        localEventHandlerConfig.fanoutBatchSize.ifPresent(builder::batchSize);
        return builder.build();
    }

    /**
     * Produces the in-process handling job that drains {@code event_notifications} rows and
     * invokes the registered handlers. Opt-in via
     * {@code ekbatan.local-event-handler.handling.enabled=true} because deployments that drain
     * notifications via an external pipeline (e.g. Kafka) should keep their
     * {@code @EkbatanEventHandler} beans for serialization-only purposes without booting an
     * in-process consumer.
     *
     * @param databaseRegistry the per-shard connection pools.
     * @param handlerRegistry the registry of event handlers.
     * @param jsonMapper the Jackson mapper used to deserialize event payloads before dispatch.
     * @param localEventHandlerConfig the parsed {@code ekbatan.local-event-handler.*} subtree.
     * @param clock the system clock used for retention windows and backoff timestamps.
     * @return the handling job, scheduled by Micronaut once started.
     */
    @Bean
    @Singleton
    @Requires(property = "ekbatan.local-event-handler.handling.enabled", value = "true")
    public EventHandlingJob ekbatanEventHandlingJob(
            DatabaseRegistry databaseRegistry,
            EventHandlerRegistry handlerRegistry,
            JsonMapper jsonMapper,
            LocalEventHandlerConfig localEventHandlerConfig,
            Clock clock) {
        var builder = EventHandlingJob.eventHandlingJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(handlerRegistry)
                .objectMapper(jsonMapper)
                .clock(clock);
        localEventHandlerConfig.handlingPollDelay.ifPresent(builder::pollDelay);
        localEventHandlerConfig.handlingBatchSize.ifPresent(builder::batchSize);
        localEventHandlerConfig.handlingMaxBackoffCap.ifPresent(builder::maxBackoffCap);
        localEventHandlerConfig.handlingRetentionWindow.ifPresent(builder::retentionWindow);
        return builder.build();
    }
}
