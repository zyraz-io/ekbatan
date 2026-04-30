package io.ekbatan.quarkus.runtime;

import io.ekbatan.bootstrap.RegistryAssembler;
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

@Singleton
public class EkbatanLocalEventHandlerConfiguration {

    @Produces
    @Singleton
    public EventHandlerRegistry ekbatanEventHandlerRegistry(@All List<EventHandler<?>> handlers) {
        return RegistryAssembler.eventHandlerRegistry(handlers);
    }

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

    // EventHandlingJob is opt-in: deployments consuming event_notifications via an external
    // pipeline (e.g. Kafka) keep their @EkbatanEventHandler beans without booting an in-process
    // consumer. @IfBuildProperty is evaluated at jar-assembly time — runtime overrides can't flip
    // this, matching Spring's @ConditionalOnProperty(havingValue="true") default-off semantic.
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
