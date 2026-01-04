package io.ekbatan.core.action.persister.event.dual_table;

import static io.ekbatan.core.action.persister.event.dual_table.ActionEventEntity.createActionEventEntity;
import static io.ekbatan.core.action.persister.event.dual_table.ModelEventEntity.createModelEventEntity;

import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.persistence.TransactionManager;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.Validate;
import tools.jackson.databind.ObjectMapper;

public class DualTableEventPersister implements EventPersister {

    private final ActionEventEntityRepository actionEventRepository;
    private final ModelEventEntityRepository modelEventRepository;
    private final ObjectMapper objectMapper;

    public DualTableEventPersister(TransactionManager transactionManager, ObjectMapper objectMapper) {
        Validate.notNull(transactionManager, "transactionManager cannot be null");
        this.actionEventRepository = new ActionEventEntityRepository(transactionManager);
        this.modelEventRepository = new ModelEventEntityRepository(transactionManager);
        this.objectMapper = Validate.notNull(objectMapper, "objectMapper cannot be null");
    }

    @Override
    public void persistActionEvents(
            String actionName,
            Instant startedDate,
            Instant completionDate,
            Object actionParams,
            Collection<ModelEvent<?>> modelEvents) {

        final var actionEventId = UUID.randomUUID();

        final var actionEvent = createActionEventEntity(
                        actionEventId, startedDate, completionDate, actionName, objectMapper.valueToTree(actionParams))
                .build();

        actionEventRepository.addNoResult(actionEvent);

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
            modelEventRepository.addAllNoResult(modelEventEntities);
        }
    }
}
