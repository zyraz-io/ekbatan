package io.ekbatan.core.repository;

import io.ekbatan.core.domain.Entity;
import io.ekbatan.core.domain.Model;
import io.ekbatan.core.domain.Persistable;
import io.ekbatan.core.internal.Validate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps each {@link Model} / {@link Entity} subclass to the {@link Repository} that persists it.
 * Passed to {@link io.ekbatan.core.action.ActionExecutor} at construction time so the
 * executor can look up the right repository when it needs to flush a typed change from the
 * {@link io.ekbatan.core.action.ActionPlan}.
 *
 * <p>Build it via {@link Builder#withModelRepository(Class, Repository)} /
 * {@link Builder#withEntityRepository(Class, Repository)}, or - when DI has already
 * collected the repositories into a {@code Collection} - via
 * {@link Builder#withRepositories(Collection)}, which dispatches each repo to the correct
 * Model/Entity setter based on its declared {@code domainClass}.
 *
 * <p>Registration is per-{@code Persistable} class, not per-shard: a single registered
 * repository can serve multiple shards via its own {@link io.ekbatan.core.shard.ShardingStrategy}.
 */
public final class RepositoryRegistry {

    /** Immutable map from persistable class to its registered repository; populated at startup, read by the executor. */
    public final Map<Class<?>, Repository<? extends Persistable<?>>> repositories;

    private RepositoryRegistry(Builder builder) {
        Validate.notNull(builder.repositories, "repositories cannot be empty");
        this.repositories = Map.copyOf(builder.repositories);
    }

    /**
     * Looks up the repository for a given persistable class.
     *
     * @param persistableClass the persistable's {@link Class}.
     * @param <P> the persistable type.
     * @return the matching repository, or {@code null} if none registered.
     */
    @SuppressWarnings("unchecked")
    public <P extends Persistable<?>> Repository<P> repository(Class<P> persistableClass) {
        return (Repository<P>) repositories.get(persistableClass);
    }

    /** Fluent builder for {@link RepositoryRegistry}. Obtain via {@link #repositoryRegistry()}. */
    public static final class Builder {
        private final Map<Class<?>, Repository<?>> repositories = new LinkedHashMap<>();

        private Builder() {}

        /** {@return a fresh builder for {@link RepositoryRegistry}} */
        public static Builder repositoryRegistry() {
            return new Builder();
        }

        /**
         * Registers a repository for a {@link Model} subclass.
         *
         * @param modelClass the model class.
         * @param repository the repository that persists it.
         * @param <M> the model type.
         * @param <R> the repository type.
         * @return this builder, for chaining.
         */
        public <M extends Model<M, ?, ?>, R extends Repository<M>> Builder withModelRepository(
                Class<M> modelClass, R repository) {
            Validate.notNull(modelClass, "modelClass cannot be null");
            Validate.notNull(repository, "repository cannot be null");
            repositories.put(modelClass, repository);
            return this;
        }

        /**
         * Registers a repository for an {@link Entity} subclass.
         *
         * @param entityClass the entity class.
         * @param repository the repository that persists it.
         * @param <E> the entity type.
         * @param <R> the repository type.
         * @return this builder, for chaining.
         */
        public <E extends Entity<E, ?, ?>, R extends Repository<E>> Builder withEntityRepository(
                Class<E> entityClass, R repository) {
            Validate.notNull(entityClass, "entityClass cannot be null");
            Validate.notNull(repository, "repository cannot be null");
            repositories.put(entityClass, repository);
            return this;
        }

        /**
         * Registers a collection of repositories - each is dispatched to either
         * {@link #withModelRepository} or {@link #withEntityRepository} based on whether its
         * declared {@code domainClass} is a {@link Model} or an {@link Entity}.
         *
         * @param repositories the repositories to register.
         * @return this builder, for chaining.
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Builder withRepositories(Collection<? extends AbstractRepository<?, ?, ?, ?>> repositories) {
            Validate.notNull(repositories, "repositories cannot be null");
            for (var repo : repositories) {
                Class<? extends Persistable<?>> domain = repo.domainClass;
                if (Model.class.isAssignableFrom(domain)) {
                    withModelRepository((Class) domain, (AbstractRepository) repo);
                } else if (Entity.class.isAssignableFrom(domain)) {
                    withEntityRepository((Class) domain, (AbstractRepository) repo);
                } else {
                    throw new IllegalStateException("Repository domainClass " + domain.getName()
                            + " is neither a Model nor an Entity - "
                            + repo.getClass().getName() + " cannot be registered.");
                }
            }
            return this;
        }

        /** {@return a configured {@link RepositoryRegistry}} */
        public RepositoryRegistry build() {
            return new RepositoryRegistry(this);
        }
    }
}
