package io.ekbatan.core.action;

import static io.ekbatan.core.action.ExecutionConfiguration.Builder.executionConfiguration;

import io.ekbatan.core.action.persister.ChangePersister;
import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.action.persister.event.single_table.SingleTableEventPersister;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.repository.RepositoryRegistry;
import java.security.Principal;
import java.time.Clock;
import org.apache.commons.lang3.Validate;
import tools.jackson.databind.ObjectMapper;

public class ActionExecutor {

    private final TransactionManager transactionManager;
    private final ActionRegistry actionRegistry;
    private final ChangePersister changePersister;
    private final Clock clock;
    private final ExecutionConfiguration defaultExecutionConfiguration;

    private ActionExecutor(Builder builder) {
        this.transactionManager = Validate.notNull(builder.transactionManager, "transactionManager is required");
        final var objectMapper = Validate.notNull(builder.objectMapper, "objectMapper is required");
        this.actionRegistry = Validate.notNull(builder.actionRegistry, "actionRegistry is required");
        this.clock = builder.clock;

        final var repositoryRegistry = Validate.notNull(builder.repositoryRegistry, "repositoryRegistry is required");

        final var eventPersister = builder.eventPersister != null
                ? builder.eventPersister
                : new SingleTableEventPersister(builder.transactionManager, objectMapper);

        this.changePersister = new ChangePersister(repositoryRegistry, eventPersister, clock);
        this.defaultExecutionConfiguration =
                Validate.notNull(builder.defaultExecutionConfiguration, "defaultExecutionConfiguration is required");
    }

    public <P, R, A extends Action<P, R>> R execute(Principal principal, Class<A> actionClass, P params)
            throws Exception {
        return execute(principal, actionClass, params, defaultExecutionConfiguration);
    }

    public <P, R, A extends Action<P, R>> R execute(
            Principal principal, Class<A> actionClass, P params, ExecutionConfiguration executionConfiguration)
            throws Exception {
        Validate.notNull(actionClass, "actionClass cannot be null");
        Validate.notNull(executionConfiguration, "executionConfiguration cannot be null");

        final var action = actionRegistry.get(actionClass);

        return Retry.<R>with(executionConfiguration.retryConfigs).execute(() -> {
            final var actionStartDate = clock.instant();
            final var result = action.perform(principal, params);
            transactionManager.inTransactionChecked(_ -> {
                changePersister.persist(action, params, actionStartDate);
            });
            return result;
        });
    }

    public static final class Builder {
        private TransactionManager transactionManager;
        private ObjectMapper objectMapper;
        private RepositoryRegistry repositoryRegistry;
        private ActionRegistry actionRegistry;
        private EventPersister eventPersister;
        private Clock clock = Clock.systemUTC();
        private ExecutionConfiguration defaultExecutionConfiguration =
                executionConfiguration().build();

        private Builder() {}

        public static Builder actionExecutor() {
            return new Builder();
        }

        public Builder transactionManager(TransactionManager transactionManager) {
            this.transactionManager = transactionManager;
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder repositoryRegistry(RepositoryRegistry repositoryRegistry) {
            this.repositoryRegistry = repositoryRegistry;
            return this;
        }

        public Builder actionRegistry(ActionRegistry actionRegistry) {
            this.actionRegistry = actionRegistry;
            return this;
        }

        public Builder eventPersister(EventPersister eventPersister) {
            this.eventPersister = eventPersister;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Builder defaultExecutionConfiguration(ExecutionConfiguration defaultExecutionConfiguration) {
            this.defaultExecutionConfiguration = defaultExecutionConfiguration;
            return this;
        }

        public ActionExecutor build() {
            return new ActionExecutor(this);
        }
    }
}
