package io.ekbatan.core.action;

import io.ekbatan.core.domain.Persistable;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.repository.Repository;
import io.ekbatan.core.repository.RepositoryRegistry;
import java.security.Principal;
import java.util.Map;
import java.util.Objects;

public class ActionExecutor {
    //    import org.slf4j.Logger;
    //    import org.slf4j.LoggerFactory;
    //    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    private final TransactionManager transactionManager;
    private final RepositoryRegistry repositoryRegistry;
    private final ActionRegistry actionRegistry;

    public ActionExecutor(
            TransactionManager transactionManager,
            RepositoryRegistry repositoryRegistry,
            ActionRegistry actionRegistry) {
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager cannot be null");
        this.repositoryRegistry = Objects.requireNonNull(repositoryRegistry, "repositoryRegistry cannot be null");
        this.actionRegistry = Objects.requireNonNull(actionRegistry, "actionRegistry cannot be null");
    }

    public <P, R, A extends Action<P, R>> R execute(Principal principal, Class<A> actionClass, P params)
            throws Exception {
        Objects.requireNonNull(actionClass, "Action class cannot be null");

        // Get the action instance from the registry
        A action = actionRegistry.get(actionClass);

        try {
            // Execute the action's business logic
            R result = action.tryPerform(principal, params);

            // Get the action plan with all planned changes
            ActionPlan actionPlan = action.plan;

            // Only proceed if there are changes to persist
            if (actionPlan.hasChanges()) {
                // Apply all changes within a transaction
                transactionManager.inTransaction(_ -> {
                    // Process all changes by entity type
                    for (Map.Entry<Class<? extends Persistable<?>>, PersistableChanges<?, ?>> entry :
                            actionPlan.changes().entrySet()) {
                        final var entityClass = entry.getKey();
                        final PersistableChanges<? extends Comparable<?>, ? extends Persistable<?>> changes =
                                entry.getValue();
                        final var repository = repositoryRegistry.repository(entityClass);

                        if (repository == null) {
                            throw new IllegalStateException(
                                    "No repository found for entity type: " + entityClass.getName());
                        }

                        applyChanges(repository, changes);
                    }

                    // Call beforeCommit hook
                    action.beforeCommit();

                    // Transaction will be committed after this lambda completes
                });
            }

            // Call afterCommit hook
            action.afterCommit();

            return result;

        } catch (Exception e) {
            // log.error("Error executing action", e);
            action.onFailure(e);
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Persistable<?>> void applyChanges(Repository<?> repository, PersistableChanges<?, ?> changes) {
        Repository<T> typedRepo = (Repository<T>) repository;
        PersistableChanges<?, T> typedChanges = (PersistableChanges<?, T>) changes;

        if (!typedChanges.additions().isEmpty()) {
            typedRepo.addAllNoResult(typedChanges.additions().values());
        }

        if (!typedChanges.updates().isEmpty()) {
            typedRepo.updateAllNoResult(typedChanges.updates().values());
        }
    }
}
