package io.ekbatan.core.action;

import static io.ekbatan.core.action.ExecutionConfiguration.Builder.executionConfiguration;

import io.ekbatan.core.action.persister.ChangePersister;
import io.ekbatan.core.action.persister.PersistableChanges;
import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.action.persister.event.single_table.SingleTableEventPersister;
import io.ekbatan.core.domain.Persistable;
import io.ekbatan.core.repository.Repository;
import io.ekbatan.core.repository.RepositoryRegistry;
import io.ekbatan.core.shard.CrossShardException;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.Validate;
import tools.jackson.databind.ObjectMapper;

public class ActionExecutor {

    private final DatabaseRegistry databaseRegistry;
    private final ActionRegistry actionRegistry;
    private final RepositoryRegistry repositoryRegistry;
    private final ChangePersister changePersister;
    private final Clock clock;
    private final ExecutionConfiguration defaultExecutionConfiguration;

    private ActionExecutor(Builder builder) {
        this.databaseRegistry = Validate.notNull(builder.databaseRegistry, "databaseRegistry is required");
        final var objectMapper = Validate.notNull(builder.objectMapper, "objectMapper is required");
        this.actionRegistry = Validate.notNull(builder.actionRegistry, "actionRegistry is required");
        this.clock = builder.clock;

        this.repositoryRegistry = Validate.notNull(builder.repositoryRegistry, "repositoryRegistry is required");

        final var eventPersister = builder.eventPersister != null
                ? builder.eventPersister
                : new SingleTableEventPersister(builder.databaseRegistry, objectMapper);

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
            persistChanges(action, params, actionStartDate, executionConfiguration);
            return result;
        });
    }

    private void persistChanges(
            Action<?, ?> action, Object params, Instant actionStartDate, ExecutionConfiguration config)
            throws Exception {
        var changesByShard = groupChangesByShard(action.plan);
        enforceSingleShard(changesByShard.keySet(), config);

        if (changesByShard.isEmpty()) {
            changesByShard = Map.of(databaseRegistry.defaultShard, action.plan.changes());
        }

        var actionEventId = java.util.UUID.randomUUID();

        for (var entry : changesByShard.entrySet()) {
            var shard = entry.getKey();
            var shardChanges = entry.getValue();
            databaseRegistry.transactionManager(shard).inTransactionChecked(_ -> {
                changePersister.persist(
                        action.getClass().getSimpleName(), params, actionStartDate, shardChanges, shard, actionEventId);
            });
        }
    }

    private Map<ShardIdentifier, Map<Class<? extends Persistable<?>>, PersistableChanges<?, ?>>> groupChangesByShard(
            ActionPlan plan) {
        var result =
                new LinkedHashMap<ShardIdentifier, Map<Class<? extends Persistable<?>>, PersistableChanges<?, ?>>>();

        if (!plan.hasChanges()) {
            return result;
        }

        for (var entry : plan.changes().entrySet()) {
            var entityClass = entry.getKey();
            var changes = entry.getValue();
            var repository = repositoryRegistry.repository(entityClass);
            groupEntityChangesByShard(repository, entityClass, changes, result);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private <ID extends Comparable<ID>, E extends Persistable<ID>> void groupEntityChangesByShard(
            Repository<?> repository,
            Class<? extends Persistable<?>> entityClass,
            PersistableChanges<?, ?> changes,
            Map<ShardIdentifier, Map<Class<? extends Persistable<?>>, PersistableChanges<?, ?>>> result) {

        var typedChanges = (PersistableChanges<ID, E>) changes;

        if (MapUtils.isNotEmpty(typedChanges.additions())) {
            for (var mapEntry : typedChanges.additions().entrySet()) {
                var shard = resolveShard(repository, mapEntry.getValue());
                var shardChanges = (PersistableChanges<ID, E>) result.computeIfAbsent(shard, _ -> new LinkedHashMap<>())
                        .computeIfAbsent(entityClass, _ -> new PersistableChanges<>());
                shardChanges.add(mapEntry.getValue());
            }
        }

        if (MapUtils.isNotEmpty(typedChanges.updates())) {
            for (var mapEntry : typedChanges.updates().entrySet()) {
                var shard = resolveShard(repository, mapEntry.getValue());
                var shardChanges = (PersistableChanges<ID, E>) result.computeIfAbsent(shard, _ -> new LinkedHashMap<>())
                        .computeIfAbsent(entityClass, _ -> new PersistableChanges<>());
                shardChanges.update(mapEntry.getValue());
            }
        }
    }

    private ShardIdentifier resolveShard(Repository<?> repository, Persistable<?> persistable) {
        var shard = repository
                .shardingStrategy()
                .resolveShardIdentifier(persistable)
                .orElse(databaseRegistry.defaultShard);
        return databaseRegistry.effectiveShard(shard);
    }

    private void enforceSingleShard(Set<ShardIdentifier> shards, ExecutionConfiguration executionConfiguration) {
        if (shards.size() > 1 && !executionConfiguration.allowCrossShard) {
            var iterator = shards.iterator();
            throw new CrossShardException(iterator.next(), iterator.next());
        }
    }

    public static final class Builder {
        private DatabaseRegistry databaseRegistry;
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

        public Builder databaseRegistry(DatabaseRegistry databaseRegistry) {
            this.databaseRegistry = databaseRegistry;
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
