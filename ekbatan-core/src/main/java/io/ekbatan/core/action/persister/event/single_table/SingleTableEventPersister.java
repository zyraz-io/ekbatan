package io.ekbatan.core.action.persister.event.single_table;

import static io.ekbatan.core.action.persister.event.single_table.ModelEventEmbedded.createModelEventEmbedded;

import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.apache.commons.lang3.Validate;
import tools.jackson.databind.ObjectMapper;

public class SingleTableEventPersister implements EventPersister {

    private static final Tracer TRACER = GlobalOpenTelemetry.get().getTracer("io.ekbatan.core", "1.0.0");

    private final ActionEventEntityRepository actionEventEntityRepository;
    private final ObjectMapper objectMapper;

    public SingleTableEventPersister(DatabaseRegistry databaseRegistry, ObjectMapper objectMapper) {
        Validate.notNull(databaseRegistry, "databaseRegistry cannot be null");
        this.objectMapper = Validate.notNull(objectMapper, "objectMapper cannot be null");
        this.actionEventEntityRepository = new ActionEventEntityRepository(databaseRegistry, objectMapper);
    }

    @Override
    public void persistActionEvents(
            String actionName,
            Instant startedDate,
            Instant completionDate,
            Object actionParams,
            Collection<ModelEvent<?>> modelEvents,
            ShardIdentifier shard,
            UUID actionEventId) {

        final var span = TRACER.spanBuilder("ekbatan.event.persist")
                .setAttribute("ekbatan.action.name", actionName)
                .setAttribute("ekbatan.event.count", modelEvents.size())
                .startSpan();
        try (var _ = span.makeCurrent()) {
            final var modelEventEmbeddedList = modelEvents.stream()
                    .map(event -> createModelEventEmbedded(
                                    UUID.randomUUID(),
                                    actionEventId,
                                    event.modelId.toString(),
                                    event.modelName,
                                    event.getClass().getSimpleName(),
                                    objectMapper.valueToTree(event),
                                    completionDate)
                            .build())
                    .toList();

            final var actionEvent = ActionEventEntity.createActionEventEntity(
                            actionEventId,
                            startedDate,
                            completionDate,
                            actionName,
                            modelEventEmbeddedList,
                            objectMapper.valueToTree(actionParams))
                    .build();

            actionEventEntityRepository.addNoResult(actionEvent, shard);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
