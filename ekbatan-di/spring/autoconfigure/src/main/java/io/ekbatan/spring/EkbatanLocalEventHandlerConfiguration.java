package io.ekbatan.spring;

import io.ekbatan.bootstrap.RegistryAssembler;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.events.localeventhandler.EventHandler;
import io.ekbatan.events.localeventhandler.EventHandlerRegistry;
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

@AutoConfiguration(after = EkbatanCoreConfiguration.class)
@ConditionalOnClass(EventHandlerRegistry.class)
@ConditionalOnBean(EventHandler.class)
public class EkbatanLocalEventHandlerConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EventHandlerRegistry ekbatanEventHandlerRegistry(List<EventHandler<?>> handlers) {
        return RegistryAssembler.eventHandlerRegistry(handlers);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventFanoutJob ekbatanEventFanoutJob(
            DatabaseRegistry databaseRegistry,
            EventHandlerRegistry handlerRegistry,
            EkbatanProperties properties,
            Clock clock) {
        var builder = EventFanoutJob.eventFanoutJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(handlerRegistry)
                .clock(clock);
        var leh = properties.localEventHandler();
        if (leh.fanoutPollDelay() != null) builder.pollDelay(leh.fanoutPollDelay());
        if (leh.fanoutBatchSize() != null) builder.batchSize(leh.fanoutBatchSize());
        return builder.build();
    }

    // EventHandlingJob is opt-in: deployments consuming event_notifications via an external
    // pipeline (e.g. Kafka) keep their @EkbatanEventHandler beans without booting an in-process
    // consumer.
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ekbatan.local-event-handler.handling", name = "enabled", havingValue = "true")
    public EventHandlingJob ekbatanEventHandlingJob(
            DatabaseRegistry databaseRegistry,
            EventHandlerRegistry handlerRegistry,
            ObjectMapper objectMapper,
            EkbatanProperties properties,
            Clock clock) {
        var builder = EventHandlingJob.eventHandlingJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(handlerRegistry)
                .objectMapper(objectMapper)
                .clock(clock);
        var leh = properties.localEventHandler();
        if (leh.handlingPollDelay() != null) builder.pollDelay(leh.handlingPollDelay());
        if (leh.handlingBatchSize() != null) builder.batchSize(leh.handlingBatchSize());
        if (leh.handlingMaxBackoffCap() != null) builder.maxBackoffCap(leh.handlingMaxBackoffCap());
        if (leh.handlingRetentionWindow() != null) builder.retentionWindow(leh.handlingRetentionWindow());
        return builder.build();
    }
}
