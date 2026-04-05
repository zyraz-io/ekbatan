package io.ekbatan.core.action.persister.event.dual_table;

import static io.ekbatan.core.action.persister.event.dual_table.ActionEventEntity.createActionEventEntity;
import static io.ekbatan.core.action.persister.event.dual_table.ModelEventEntity.createModelEventEntity;

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
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.Validate;
import tools.jackson.databind.ObjectMapper;

public class DualTableEventPersister implements EventPersister {

    private static final Tracer TRACER = GlobalOpenTelemetry.get().getTracer("io.ekbatan.core", "1.0.0");

    private final ActionEventEntityRepository actionEventRepository;
    private final ModelEventEntityRepository modelEventRepository;
    private final ObjectMapper objectMapper;

    public DualTableEventPersister(DatabaseRegistry databaseRegistry, ObjectMapper objectMapper) {
        Validate.notNull(databaseRegistry, "databaseRegistry cannot be null");
        this.actionEventRepository = new ActionEventEntityRepository(databaseRegistry);
        this.modelEventRepository = new ModelEventEntityRepository(databaseRegistry);
        this.objectMapper = Validate.notNull(objectMapper, "objectMapper cannot be null");
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
            final var actionEvent = createActionEventEntity(
                            actionEventId,
                            startedDate,
                            completionDate,
                            actionName,
                            objectMapper.valueToTree(actionParams))
                    .build();

            actionEventRepository.addNoResult(actionEvent, shard);

            if (CollectionUtils.isNotEmpty(modelEvents)) {
                final var modelEventEntities = modelEvents.stream()
                        .map(event -> createModelEventEntity(
                                        UUID.randomUUID(),
                                        actionEventId,
                                        event.modelId.toString(),
                                        event.modelName,
                                        event.getClass().getSimpleName(),
                                        objectMapper.valueToTree(event),
                                        completionDate)
                                .build())
                        .toList();
                modelEventRepository.addAllNoResult(modelEventEntities, shard);
            }
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
