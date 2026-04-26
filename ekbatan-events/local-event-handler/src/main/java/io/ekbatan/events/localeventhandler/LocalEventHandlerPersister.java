package io.ekbatan.events.localeventhandler;

import static io.ekbatan.events.localeventhandler.model.EventEntity.createEventEntity;

import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.events.localeventhandler.model.EventEntity;
import io.ekbatan.events.localeventhandler.repository.EventEntityRepository;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.commons.lang3.Validate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link EventPersister} for the in-process local-event-handler path.
 *
 * <p>Functionally equivalent to {@code SingleTableJsonEventPersister} on the write path —
 * both produce one {@code eventlog.events} row per emitted event (plus a sentinel row for
 * actions that emit none). The difference is operational: this persister is paired with
 * the {@code delivered} column and the in-process fan-out / dispatch jobs in this module.
 * {@code delivered} is never written explicitly; the schema default ({@code FALSE}) applies.
 *
 * <p>Mutually exclusive with the Debezium/Kafka path: the {@code delivered} column mutation
 * means CDC tooling tailing the same table would see UPDATE events alongside the INSERTs.
 * Apps choose one persister or the other.
 */
public final class LocalEventHandlerPersister implements EventPersister {

    private static final Tracer TRACER =
            GlobalOpenTelemetry.get().getTracer("io.ekbatan.events.localeventhandler", "1.0.0");

    private final EventEntityRepository eventEntityRepository;
    private final ObjectMapper objectMapper;

    public LocalEventHandlerPersister(DatabaseRegistry databaseRegistry, ObjectMapper objectMapper) {
        Validate.notNull(databaseRegistry, "databaseRegistry cannot be null");
        this.objectMapper = Validate.notNull(objectMapper, "objectMapper cannot be null");
        this.eventEntityRepository = new EventEntityRepository(databaseRegistry);
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
            final ObjectNode serializedParams = objectMapper.valueToTree(actionParams);

            final List<EventEntity> entities;
            if (modelEvents.isEmpty()) {
                // Sentinel row: no event_type / model fields so the fan-out filter
                // (event_type IS NOT NULL) skips it.
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
                                completionDate,
                                false)
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
                                        objectMapper.valueToTree(event),
                                        completionDate,
                                        false)
                                .build())
                        .collect(Collectors.toList());
            }

            eventEntityRepository.addAllNoResult(entities, shard);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
