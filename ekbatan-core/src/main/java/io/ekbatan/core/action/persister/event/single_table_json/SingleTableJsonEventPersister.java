package io.ekbatan.core.action.persister.event.single_table_json;

import static io.ekbatan.core.action.persister.event.single_table_json.EventEntity.createEventEntity;

import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.Validate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public class SingleTableJsonEventPersister implements EventPersister {

    private static final Tracer TRACER = GlobalOpenTelemetry.get().getTracer("io.ekbatan.core", "1.0.0");

    private final EventEntityRepository eventRepository;
    private final ObjectMapper objectMapper;

    public SingleTableJsonEventPersister(DatabaseRegistry databaseRegistry, ObjectMapper objectMapper) {
        Validate.notNull(databaseRegistry, "databaseRegistry cannot be null");
        this.eventRepository = new EventEntityRepository(databaseRegistry);
        this.objectMapper = Validate.notNull(objectMapper, "objectMapper cannot be null");
    }

    @Override
    public void persistActionEvents(
            String namespace,
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
            final var serializedParams = (ObjectNode) objectMapper.valueToTree(actionParams);

            final List<EventEntity> entities;
            if (modelEvents.isEmpty()) {
                entities = List.of(createEventEntity(
                                UUID.randomUUID(),
                                namespace,
                                actionEventId,
                                actionName,
                                serializedParams,
                                startedDate,
                                completionDate,
                                null,
                                null,
                                null,
                                null,
                                completionDate)
                        .build());
            } else {
                entities = modelEvents.stream()
                        .map(event -> createEventEntity(
                                        UUID.randomUUID(),
                                        namespace,
                                        actionEventId,
                                        actionName,
                                        serializedParams,
                                        startedDate,
                                        completionDate,
                                        event.modelId,
                                        event.modelName,
                                        event.getClass().getSimpleName(),
                                        (ObjectNode) objectMapper.valueToTree(event),
                                        completionDate)
                                .build())
                        .collect(java.util.stream.Collectors.toList());
            }

            eventRepository.addAllNoResult(entities, shard);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
