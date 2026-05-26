package io.ekbatan.core.action.persister;

import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.domain.Model;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.domain.Persistable;
import io.ekbatan.core.repository.Repository;
import io.ekbatan.core.repository.RepositoryRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flushes a per-shard slice of an {@link io.ekbatan.core.action.ActionPlan} to storage:
 * dispatches each {@link PersistableChanges} to its corresponding {@link Repository} for the
 * domain rows, and forwards {@link io.ekbatan.core.domain.ModelEvent}s (extracted only from
 * {@link io.ekbatan.core.domain.Model} subclasses) to the configured {@link EventPersister}.
 *
 * <p>This is an internal helper of {@link io.ekbatan.core.action.ActionExecutor}; application
 * code doesn't construct one directly. Documented here because the persister surface - what
 * gets written, in what order, with what correlation IDs - is part of the framework's
 * persistence contract.
 *
 * <p>Domain rows are written first, then events, all within the per-shard transaction the
 * executor opens. A failure during event persistence rolls the entire transaction back, so
 * the outbox is always consistent with the domain rows it describes.
 */
public class ChangePersister {

    private static final Logger LOG = LoggerFactory.getLogger(ChangePersister.class);

    private final RepositoryRegistry repositoryRegistry;
    private final EventPersister eventPersister;
    private final Clock clock;

    /**
     * Constructs the persister; all arguments are required.
     *
     * @param repositoryRegistry the registry used to look up a repository per entity type.
     * @param eventPersister the persister that writes the outbox rows.
     * @param clock the system clock used for the action's completion timestamp.
     */
    public ChangePersister(RepositoryRegistry repositoryRegistry, EventPersister eventPersister, Clock clock) {
        this.repositoryRegistry = Validate.notNull(repositoryRegistry, "repositoryRegistry cannot be null");
        this.eventPersister = Validate.notNull(eventPersister, "eventPersister cannot be null");
        this.clock = Validate.notNull(clock, "clock cannot be null");
    }

    /**
     * Flushes the given per-shard plan slice: writes domain rows via each repository, then
     * the events via {@link EventPersister}. Runs inside the executor's per-shard transaction.
     *
     * @param namespace the namespace recorded on every persisted event.
     * @param actionName simple class name of the producing action.
     * @param params the action's typed params (serialized into the eventlog).
     * @param actionStartDate when the action's {@code perform()} began.
     * @param changes the per-entity-type changes to flush.
     * @param shard the shard this slice targets.
     * @param actionEventId the executor-generated correlation id for this action invocation.
     */
    public void persist(
            String namespace,
            String actionName,
            Object params,
            Instant actionStartDate,
            Map<Class<? extends Persistable<?>>, PersistableChanges<?, ?>> changes,
            ShardIdentifier shard,
            java.util.UUID actionEventId) {
        final var actionCompletionDate = clock.instant();
        final var modelEvents = new ArrayList<ModelEvent<?>>();

        final var addCount =
                changes.values().stream().mapToInt(c -> c.additions().size()).sum();
        final var updateCount =
                changes.values().stream().mapToInt(c -> c.updates().size()).sum();
        LOG.debug("Persisting {} additions, {} updates", addCount, updateCount);

        for (var entry : changes.entrySet()) {
            final var entityClass = entry.getKey();
            final var entityChanges = entry.getValue();
            final var repository = repositoryRegistry.repository(entityClass);

            if (Model.class.isAssignableFrom(entityClass)) {
                modelEvents.addAll(extractModelEvents(entityChanges));
            }

            if (repository == null) {
                throw new IllegalStateException("No repository found for entity type: " + entityClass.getName());
            }

            applyChanges(repository, entityChanges);
        }

        eventPersister.persistActionEvents(
                namespace,
                actionName,
                actionStartDate,
                actionCompletionDate,
                params,
                modelEvents,
                shard,
                actionEventId);
        LOG.debug("Persisted events for action {} [actionEventId={}]", actionName, actionEventId);
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
