package io.ekbatan.core.action;

import io.ekbatan.core.action.persister.ChangePersister;
import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.repository.RepositoryRegistry;
import java.security.Principal;
import java.time.Instant;
import org.apache.commons.lang3.Validate;

public class ActionExecutor {
    //    import org.slf4j.Logger;
    //    import org.slf4j.LoggerFactory;
    //    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    private final TransactionManager transactionManager;
    private final ActionRegistry actionRegistry;
    private final ChangePersister changePersister;

    public ActionExecutor(
            TransactionManager transactionManager,
            EventPersister eventPersister,
            RepositoryRegistry repositoryRegistry,
            ActionRegistry actionRegistry) {
        this.transactionManager = Validate.notNull(transactionManager, "transactionManager cannot be null");
        this.actionRegistry = Validate.notNull(actionRegistry, "actionRegistry cannot be null");
        this.changePersister = new ChangePersister(
                Validate.notNull(repositoryRegistry, "repositoryRegistry cannot be null"), eventPersister);
    }

    public <P, R, A extends Action<P, R>> R execute(Principal principal, Class<A> actionClass, P params)
            throws Exception {
        Validate.notNull(actionClass, "Action class cannot be null");

        final var action = actionRegistry.get(actionClass);

        try {
            final var actionStartDate = Instant.now();

            final var result = action.perform(principal, params);

            transactionManager.inTransaction(_ -> {
                changePersister.persist(action, params, actionStartDate);
            });

            return result;

        } catch (Exception e) {
            action.onFailure(e);
            throw e;
        }
    }
}
