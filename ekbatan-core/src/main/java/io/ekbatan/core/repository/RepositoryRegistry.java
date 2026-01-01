package io.ekbatan.core.repository;

import com.google.common.collect.ImmutableMap;
import io.ekbatan.core.domain.Entity;
import io.ekbatan.core.domain.Model;
import io.ekbatan.core.domain.Persistable;
import java.util.Map;
import org.apache.commons.lang3.Validate;

public final class RepositoryRegistry {
    public final Map<Class<?>, Repository<? extends Persistable<?>>> repositories;
    public final ActionEventEntityRepository actionEventRepository;
    public final ModelEventEntityRepository modelEventRepository;

    private RepositoryRegistry(Builder builder) {
        Validate.notNull(builder.repositories, "repositories cannot be empty");
        this.repositories = builder.repositories.build();
        this.actionEventRepository =
                Validate.notNull(builder.actionEventRepository, "actionEventRepository cannot be null");
        this.modelEventRepository =
                Validate.notNull(builder.modelEventRepository, "modelEventRepository cannot be null");
    }

    @SuppressWarnings("unchecked")
    public <P extends Persistable<?>> Repository<P> repository(Class<P> persistableClass) {
        return (Repository<P>) repositories.get(persistableClass);
    }

    public static final class Builder {
        private final ImmutableMap.Builder<Class<?>, Repository<?>> repositories = ImmutableMap.builder();
        private ActionEventEntityRepository actionEventRepository;
        private ModelEventEntityRepository modelEventRepository;

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

        public Builder withActionEventRepository(ActionEventEntityRepository actionEventRepository) {
            Validate.notNull(actionEventRepository, "eventRepository cannot be null");
            this.actionEventRepository = actionEventRepository;
            return this;
        }

        public Builder withModelEventRepository(ModelEventEntityRepository modelEventRepository) {
            Validate.notNull(modelEventRepository, "eventRepository cannot be null");
            this.modelEventRepository = modelEventRepository;
            return this;
        }

        public RepositoryRegistry build() {
            return new RepositoryRegistry(this);
        }
    }
}
