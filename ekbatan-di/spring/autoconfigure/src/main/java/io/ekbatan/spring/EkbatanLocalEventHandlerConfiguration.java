package io.ekbatan.spring;

import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.events.localeventhandler.EventHandler;
import io.ekbatan.events.localeventhandler.EventHandlerRegistry;
import io.ekbatan.events.localeventhandler.config.LocalEventHandlerConfig;
import io.ekbatan.events.localeventhandler.job.EventFanoutJob;
import io.ekbatan.events.localeventhandler.job.EventHandlingJob;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring Boot auto-configuration for Ekbatan's in-process event handler dispatch. Activates
 * only when both conditions hold: {@code EventHandlerRegistry} is on the classpath (the
 * {@code ekbatan-local-event-handler} dependency is present), and the application defines at
 * least one {@code @EkbatanEventHandler} bean.
 *
 * <p>Produces an {@link EventHandlerRegistry} from the discovered {@code EventHandler} beans
 * plus an {@link EventFanoutJob} / {@link EventHandlingJob} pair that polls the outbox and
 * delivers events to handlers. Set {@code ekbatan.local-event-handler.handling.enabled=true}
 * to activate the handling job (the fanout job is always active when the registry is built).
 */
@AutoConfiguration(after = EkbatanCoreConfiguration.class)
@ConditionalOnClass(EventHandlerRegistry.class)
@ConditionalOnBean(EventHandler.class)
public class EkbatanLocalEventHandlerConfiguration {

    /** Required by Spring; the container instantiates this auto-configuration class to invoke its {@code @Bean} methods. */
    public EkbatanLocalEventHandlerConfiguration() {}

    /**
     * Bundles every {@code @EkbatanEventHandler}-annotated bean discovered by Spring into a
     * single {@link EventHandlerRegistry} used by the fanout + handling jobs.
     *
     * @param handlers the application's event-handler beans, injected by Spring.
     * @return the registry consulted during fanout to materialize per-handler notification rows.
     */
    @Bean
    @ConditionalOnMissingBean
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
     * @return the fanout job, scheduled by Spring once started.
     */
    @Bean
    @ConditionalOnMissingBean
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
     * @param objectMapper the Jackson mapper used to deserialize event payloads before dispatch.
     * @param localEventHandlerConfig the parsed {@code ekbatan.local-event-handler.*} subtree.
     * @param clock the system clock used for retention windows and backoff timestamps.
     * @return the handling job, scheduled by Spring once started.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ekbatan.local-event-handler.handling", name = "enabled", havingValue = "true")
    public EventHandlingJob ekbatanEventHandlingJob(
            DatabaseRegistry databaseRegistry,
            EventHandlerRegistry handlerRegistry,
            ObjectMapper objectMapper,
            LocalEventHandlerConfig localEventHandlerConfig,
            Clock clock) {
        var builder = EventHandlingJob.eventHandlingJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(handlerRegistry)
                .objectMapper(objectMapper)
                .clock(clock);
        localEventHandlerConfig.handlingPollDelay.ifPresent(builder::pollDelay);
        localEventHandlerConfig.handlingBatchSize.ifPresent(builder::batchSize);
        localEventHandlerConfig.handlingMaxBackoffCap.ifPresent(builder::maxBackoffCap);
        localEventHandlerConfig.handlingRetentionWindow.ifPresent(builder::retentionWindow);
        return builder.build();
    }
}
