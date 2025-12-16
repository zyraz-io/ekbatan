package io.ekbatan.core.repository;

import com.google.common.collect.ImmutableMap;
import io.ekbatan.core.domain.Entity;
import io.ekbatan.core.domain.Model;
import io.ekbatan.core.domain.Persistable;
import java.util.Map;
import org.apache.commons.lang3.Validate;

public final class RepositoryRegistry {
    public final Map<Class<?>, Repository<? extends Persistable<?>>> repositories;

    private RepositoryRegistry(Builder builder) {
        Validate.notNull(builder.repositories, "repositories should not be empty");
        this.repositories = builder.repositories.build();
    }

    @SuppressWarnings("unchecked")
    public <P extends Persistable<?>> Repository<P> repository(Class<P> persistableClass) {
        return (Repository<P>) repositories.get(persistableClass);
    }

    public static final class Builder {
        private final ImmutableMap.Builder<Class<?>, Repository<?>> repositories = ImmutableMap.builder();

        private Builder() {}

        public static Builder repositoryRegistry() {
            return new Builder();
        }

        public <M extends Model<M, ?, ?>, R extends Repository<M>> Builder withModelRepository(
                Class<M> modelClass, R repository) {
            Validate.notNull(modelClass, "modelClass cannot be null");
            Validate.notNull(repository, "repository cannot be null");
            repositories.put(modelClass, repository);
            return this;
        }

        public <E extends Entity<E, ?, ?>, R extends Repository<E>> Builder withEntityRepository(
                Class<E> entityClass, R repository) {
            Validate.notNull(entityClass, "entityClass cannot be null");
            Validate.notNull(repository, "repository cannot be null");
            repositories.put(entityClass, repository);
            return this;
        }

        public RepositoryRegistry build() {
            return new RepositoryRegistry(this);
        }
    }
}
