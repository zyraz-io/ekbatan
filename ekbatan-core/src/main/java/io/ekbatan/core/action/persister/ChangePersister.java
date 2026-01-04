package io.ekbatan.core.action.persister;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.domain.Model;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.domain.Persistable;
import io.ekbatan.core.repository.Repository;
import io.ekbatan.core.repository.RepositoryRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.Validate;

public class ChangePersister {

    private final RepositoryRegistry repositoryRegistry;
    private final EventPersister eventPersister;

    public ChangePersister(RepositoryRegistry repositoryRegistry, EventPersister eventPersister) {
        this.repositoryRegistry = Validate.notNull(repositoryRegistry, "repositoryRegistry cannot be null");
        this.eventPersister = Validate.notNull(eventPersister, "eventPersister cannot be null");
    }

    public void persist(Action<?, ?> action, Object params, Instant actionStartDate) {
        final var actionCompletionDate = Instant.now();
        final var actionPlan = action.plan;
        final var modelEvents = new ArrayList<ModelEvent<?>>();

        if (actionPlan.hasChanges()) {

            for (var entry : actionPlan.changes().entrySet()) {
                final var entityClass = entry.getKey();
                final var changes = entry.getValue();
                final var repository = repositoryRegistry.repository(entityClass);

                if (Model.class.isAssignableFrom(entityClass)) {
                    modelEvents.addAll(extractModelEvents(changes));
                }

                if (repository == null) {
                    throw new IllegalStateException("No repository found for entity type: " + entityClass.getName());
                }

                applyChanges(repository, changes);
            }
        }

        eventPersister.persistActionEvents(
                action.getClass().getName(), actionStartDate, actionCompletionDate, params, modelEvents);
    }

    private List<ModelEvent<?>> extractModelEvents(
            PersistableChanges<? extends Comparable<?>, ? extends Persistable<?>> changes) {

        final var modelEvents = new ArrayList<ModelEvent<?>>();

        if (MapUtils.isNotEmpty(changes.additions())) {
            for (var persistable : changes.additions().values()) {
                final var model = (Model<?, ?, ?>) persistable;
                modelEvents.addAll(model.events);
            }
        }

        if (MapUtils.isNotEmpty(changes.updates())) {
            for (var persistable : changes.updates().values()) {
                final var model = (Model<?, ?, ?>) persistable;
                modelEvents.addAll(model.events);
            }
        }

        return modelEvents;
    }

    @SuppressWarnings("unchecked")
    private <T extends Persistable<?>> void applyChanges(Repository<?> repository, PersistableChanges<?, ?> changes) {
        final var typedRepo = (Repository<T>) repository;
        final var typedChanges = (PersistableChanges<?, T>) changes;

        if (MapUtils.isNotEmpty(typedChanges.additions())) {
            typedRepo.addAllNoResult(typedChanges.additions().values());
        }

        if (MapUtils.isNotEmpty(typedChanges.updates())) {
            typedRepo.updateAllNoResult(typedChanges.updates().values());
        }
    }
}
