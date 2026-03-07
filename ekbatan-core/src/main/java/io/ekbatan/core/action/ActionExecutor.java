package io.ekbatan.core.action;

import io.ekbatan.core.action.persister.ChangePersister;
import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.action.persister.event.single_table.SingleTableEventPersister;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.repository.RepositoryRegistry;
import java.security.Principal;
import java.time.Instant;
import org.apache.commons.lang3.Validate;
import tools.jackson.databind.ObjectMapper;

public class ActionExecutor {

    private final TransactionManager transactionManager;
    private final ActionRegistry actionRegistry;
    private final ChangePersister changePersister;

    private ActionExecutor(Builder builder) {
        this.transactionManager = Validate.notNull(builder.transactionManager, "transactionManager is required");
        final var objectMapper = Validate.notNull(builder.objectMapper, "objectMapper is required");
        this.actionRegistry = Validate.notNull(builder.actionRegistry, "actionRegistry is required");

        final var repositoryRegistry = Validate.notNull(builder.repositoryRegistry, "repositoryRegistry is required");

        final var eventPersister = builder.eventPersister != null
                ? builder.eventPersister
                : new SingleTableEventPersister(builder.transactionManager, objectMapper);

        this.changePersister = new ChangePersister(repositoryRegistry, eventPersister);
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

    public static final class Builder {
        private TransactionManager transactionManager;
        private ObjectMapper objectMapper;
        private RepositoryRegistry repositoryRegistry;
        private ActionRegistry actionRegistry;
        private EventPersister eventPersister;

        private Builder() {}

        public static Builder actionExecutor() {
            return new Builder();
        }

        public Builder transactionManager(TransactionManager transactionManager) {
            this.transactionManager = Validate.notNull(transactionManager, "transactionManager cannot be null");
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = Validate.notNull(objectMapper, "objectMapper cannot be null");
            return this;
        }

        public Builder repositoryRegistry(RepositoryRegistry repositoryRegistry) {
            this.repositoryRegistry = Validate.notNull(repositoryRegistry, "repositoryRegistry cannot be null");
            return this;
        }

        public Builder actionRegistry(ActionRegistry actionRegistry) {
            this.actionRegistry = Validate.notNull(actionRegistry, "actionRegistry cannot be null");
            return this;
        }

        public Builder eventPersister(EventPersister eventPersister) {
            this.eventPersister = Validate.notNull(eventPersister, "eventPersister cannot be null");
            return this;
        }

        public ActionExecutor build() {
            return new ActionExecutor(this);
        }
    }
}
