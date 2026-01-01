package io.ekbatan.core.action;

import static io.ekbatan.core.domain.event.ActionEventEntity.createActionEventEntity;
import static io.ekbatan.core.domain.event.ModelEventEntity.createModelEventEntity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ekbatan.core.domain.Model;
import io.ekbatan.core.domain.Persistable;
import io.ekbatan.core.domain.event.ActionEventEntity;
import io.ekbatan.core.domain.event.ModelEventEntity;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.repository.ActionEventEntityRepository;
import io.ekbatan.core.repository.ModelEventEntityRepository;
import io.ekbatan.core.repository.Repository;
import io.ekbatan.core.repository.RepositoryRegistry;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.Validate;

public class ActionExecutor {
    //    import org.slf4j.Logger;
    //    import org.slf4j.LoggerFactory;
    //    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    private final TransactionManager transactionManager;
    private final RepositoryRegistry repositoryRegistry;
    private final ActionRegistry actionRegistry;
    private final ActionEventEntityRepository actionEventRepository;
    private final ModelEventEntityRepository modelEventRepository;
    private final ObjectMapper objectMapper;

    public ActionExecutor(
            TransactionManager transactionManager,
            RepositoryRegistry repositoryRegistry,
            ActionRegistry actionRegistry) {
        this.objectMapper = new ObjectMapper();
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager cannot be null");
        this.repositoryRegistry = Objects.requireNonNull(repositoryRegistry, "repositoryRegistry cannot be null");
        this.actionRegistry = Objects.requireNonNull(actionRegistry, "actionRegistry cannot be null");

        this.actionEventRepository =
                Validate.notNull(repositoryRegistry.actionEventRepository, "actionEventRepository cannot be null");
        this.modelEventRepository =
                Validate.notNull(repositoryRegistry.modelEventRepository, "modelEventRepository cannot be null");
    }

    public <P, R, A extends Action<P, R>> R execute(Principal principal, Class<A> actionClass, P params)
            throws Exception {
        Validate.notNull(actionClass, "Action class cannot be null");

        final var actionStartDate = Instant.now();

        final var action = actionRegistry.get(actionClass);

        try {
            final var result = action.perform(principal, params);

            final var actionPlan = action.plan;

            transactionManager.inTransaction(_ -> {
                final var actionCompletionDate = Instant.now();

                final var actionEvent = createActionEventEntity(
                                UUID.randomUUID(),
                                actionStartDate,
                                actionCompletionDate,
                                action.getClass().getName(),
                                objectMapper.valueToTree(params))
                        .build();

                actionEventRepository.addNoResult(actionEvent);

                if (actionPlan.hasChanges()) {

                    final var modelEventEntities = new ArrayList<ModelEventEntity>();

                    for (var entry : actionPlan.changes().entrySet()) {
                        final var entityClass = entry.getKey();
                        final var changes = entry.getValue();
                        final var repository = repositoryRegistry.repository(entityClass);

                        if (Model.class.isAssignableFrom(entityClass)) {
                            modelEventEntities.addAll(extractModelEvents(changes, actionEvent, actionCompletionDate));
                        }

                        if (repository == null) {
                            throw new IllegalStateException(
                                    "No repository found for entity type: " + entityClass.getName());
                        }

                        applyChanges(repository, changes);
                    }

                    modelEventRepository.addAllNoResult(modelEventEntities);
                }
            });

            return result;

        } catch (Exception e) {
            action.onFailure(e);
            throw e;
        }
    }

    private ArrayList<ModelEventEntity> extractModelEvents(
            PersistableChanges<? extends Comparable<?>, ? extends Persistable<?>> changes,
            ActionEventEntity actionEvent,
            Instant actionCompletionDate) {

        final var modelEventEntities = new ArrayList<ModelEventEntity>();

        for (var persistable : changes.additions().values()) {
            final var model = (Model<?, ?, ?>) persistable;
            for (var event : model.events) {
                modelEventEntities.add(createModelEventEntity(
                                UUID.randomUUID(),
                                actionEvent.id,
                                model.id.toString(),
                                event.modelName,
                                event.getClass().getSimpleName(),
                                objectMapper.valueToTree(event),
                                actionCompletionDate)
                        .build());
            }
        }

        for (var persistable : changes.updates().values()) {
            final var model = (Model<?, ?, ?>) persistable;
            for (var event : model.events) {
                modelEventEntities.add(createModelEventEntity(
                                UUID.randomUUID(),
                                actionEvent.id,
                                event.modelId.toString(),
                                event.modelName,
                                event.getClass().getSimpleName(),
                                objectMapper.valueToTree(event),
                                actionCompletionDate)
                        .build());
            }
        }

        return modelEventEntities;
    }

    @SuppressWarnings("unchecked")
    private <T extends Persistable<?>> void applyChanges(Repository<?> repository, PersistableChanges<?, ?> changes) {
        final var typedRepo = (Repository<T>) repository;
        final var typedChanges = (PersistableChanges<?, T>) changes;

        if (!typedChanges.additions().isEmpty()) {
            typedRepo.addAllNoResult(typedChanges.additions().values());
        }

        if (!typedChanges.updates().isEmpty()) {
            typedRepo.updateAllNoResult(typedChanges.updates().values());
        }
    }
}
