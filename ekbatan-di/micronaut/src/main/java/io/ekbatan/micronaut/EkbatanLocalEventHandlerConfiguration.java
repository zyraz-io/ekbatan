package io.ekbatan.micronaut;

import io.ekbatan.bootstrap.RegistryAssembler;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.events.localeventhandler.EventHandler;
import io.ekbatan.events.localeventhandler.EventHandlerRegistry;
import io.ekbatan.events.localeventhandler.job.EventFanoutJob;
import io.ekbatan.events.localeventhandler.job.EventHandlingJob;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

@Factory
@Requires(classes = EventHandlerRegistry.class)
public class EkbatanLocalEventHandlerConfiguration {

    @Bean
    @Singleton
    public EventHandlerRegistry ekbatanEventHandlerRegistry(List<EventHandler<?>> handlers) {
        return RegistryAssembler.eventHandlerRegistry(handlers);
    }

    @Bean
    @Singleton
    public EventFanoutJob ekbatanEventFanoutJob(
            DatabaseRegistry databaseRegistry,
            EventHandlerRegistry handlerRegistry,
            EkbatanProperties properties,
            Clock clock) {
        var builder = EventFanoutJob.eventFanoutJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(handlerRegistry)
                .clock(clock);
        var leh = properties.getLocalEventHandler();
        if (leh.getFanoutPollDelay() != null) builder.pollDelay(leh.getFanoutPollDelay());
        if (leh.getFanoutBatchSize() != null) builder.batchSize(leh.getFanoutBatchSize());
        return builder.build();
    }

    // EventHandlingJob is opt-in: deployments consuming event_notifications via an external
    // pipeline (e.g. Kafka) keep their @EkbatanEventHandler beans without booting an in-process
    // consumer.
    @Bean
    @Singleton
    @Requires(property = "ekbatan.local-event-handler.handling.enabled", value = "true")
    public EventHandlingJob ekbatanEventHandlingJob(
            DatabaseRegistry databaseRegistry,
            EventHandlerRegistry handlerRegistry,
            JsonMapper jsonMapper,
            EkbatanProperties properties,
            Clock clock) {
        var builder = EventHandlingJob.eventHandlingJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(handlerRegistry)
                .objectMapper(jsonMapper)
                .clock(clock);
        var leh = properties.getLocalEventHandler();
        if (leh.getHandlingPollDelay() != null) builder.pollDelay(leh.getHandlingPollDelay());
        if (leh.getHandlingBatchSize() != null) builder.batchSize(leh.getHandlingBatchSize());
        if (leh.getHandlingMaxBackoffCap() != null) builder.maxBackoffCap(leh.getHandlingMaxBackoffCap());
        if (leh.getHandlingRetentionWindow() != null) builder.retentionWindow(leh.getHandlingRetentionWindow());
        return builder.build();
    }
}
