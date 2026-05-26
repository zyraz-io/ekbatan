package io.ekbatan.quarkus.runtime;

import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.events.localeventhandler.EventHandler;
import io.ekbatan.events.localeventhandler.EventHandlerRegistry;
import io.ekbatan.events.localeventhandler.job.EventFanoutJob;
import io.ekbatan.events.localeventhandler.job.EventHandlingJob;
import io.quarkus.arc.All;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * Quarkus runtime CDI producer class for Ekbatan's in-process event handler dispatch.
 * Discovered at deployment time only when {@code EventHandlerRegistry} is present at runtime
 * (see {@code EkbatanProcessor.registerLocalEventHandlerProducers}); produces
 * {@link EventHandlerRegistry}, {@link EventFanoutJob}, and {@link EventHandlingJob} from
 * user-supplied {@code @EkbatanEventHandler} beans.
 *
 * <p>The {@link EventHandlingJob} is gated by {@code @IfBuildProperty}, evaluated at jar /
 * test bootstrap - set {@code ekbatan.local-event-handler.handling.enabled=true} in
 * {@code application.properties} to activate.
 */
@Singleton
public class EkbatanLocalEventHandlerConfiguration {

    /** Required by CDI; the container instantiates this bean class to invoke its producer methods. */
    public EkbatanLocalEventHandlerConfiguration() {}

    /**
     * Bundles every {@code @EkbatanEventHandler}-annotated bean discovered at deployment into a
     * single {@link EventHandlerRegistry} used by the fanout + handling jobs.
     *
     * @param handlers the application's event-handler beans, injected via Arc {@code @All}.
     * @return the registry consulted during fanout to materialize per-handler notification rows.
     */
    @Produces
    @Singleton
    public EventHandlerRegistry ekbatanEventHandlerRegistry(@All List<EventHandler<?>> handlers) {
        return EventHandlerRegistry.eventHandlerRegistry()
                .withHandlers(handlers)
                .build();
    }

    /**
     * Produces the fanout job that copies newly-committed events from the eventlog into per-handler
     * {@code event_notifications} rows. Tuning comes from {@code ekbatan.local-event-handler.fanout-*}.
     *
     * @param databaseRegistry the per-shard connection pools.
     * @param handlerRegistry the registry of event handlers.
     * @param config the Ekbatan runtime configuration (reads {@code ekbatan.local-event-handler.*}).
     * @param clock the system clock used for fanout cursor timestamps.
     * @return the fanout job, scheduled by Quarkus once started.
     */
    @Produces
    @Singleton
    public EventFanoutJob ekbatanEventFanoutJob(
            DatabaseRegistry databaseRegistry,
            EventHandlerRegistry handlerRegistry,
            EkbatanProperties config,
            Clock clock) {
        var builder = EventFanoutJob.eventFanoutJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(handlerRegistry)
                .clock(clock);
        var leh = config.localEventHandler();
        leh.fanoutPollDelay().ifPresent(builder::pollDelay);
        leh.fanoutBatchSize().ifPresent(builder::batchSize);
        return builder.build();
    }

    /**
     * Produces the in-process handling job that drains {@code event_notifications} rows and invokes
     * the registered handlers. Opt-in via {@code ekbatan.local-event-handler.handling.enabled=true}
     * because deployments that drain notifications via an external pipeline (e.g. Kafka) should
     * keep their {@code @EkbatanEventHandler} beans for serialization-only purposes without
     * booting an in-process consumer.
     *
     * <p>{@link IfBuildProperty} is evaluated at jar-assembly time - runtime overrides can't flip
     * this, matching Spring's {@code @ConditionalOnProperty(havingValue="true")} default-off semantic.
     *
     * @param databaseRegistry the per-shard connection pools.
     * @param handlerRegistry the registry of event handlers.
     * @param jsonMapper the Jackson mapper used to deserialize event payloads before dispatch.
     * @param config the Ekbatan runtime configuration (reads {@code ekbatan.local-event-handler.*}).
     * @param clock the system clock used for retention windows and backoff timestamps.
     * @return the handling job, scheduled by Quarkus once started.
     */
    @Produces
    @Singleton
    @IfBuildProperty(
            name = "ekbatan.local-event-handler.handling.enabled",
            stringValue = "true",
            enableIfMissing = false)
    public EventHandlingJob ekbatanEventHandlingJob(
            DatabaseRegistry databaseRegistry,
            EventHandlerRegistry handlerRegistry,
            JsonMapper jsonMapper,
            EkbatanProperties config,
            Clock clock) {
        var builder = EventHandlingJob.eventHandlingJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(handlerRegistry)
                .objectMapper(jsonMapper)
                .clock(clock);
        var leh = config.localEventHandler();
        leh.handlingPollDelay().ifPresent(builder::pollDelay);
        leh.handlingBatchSize().ifPresent(builder::batchSize);
        leh.handlingMaxBackoffCap().ifPresent(builder::maxBackoffCap);
        leh.handlingRetentionWindow().ifPresent(builder::retentionWindow);
        return builder.build();
    }
}
