package io.ekbatan.core.repository;

import static io.ekbatan.core.repository.RepositoryRegistry.Builder.repositoryRegistry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.ekbatan.core.domain.Entity;
import io.ekbatan.core.domain.GenericState;
import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.Model;
import org.junit.jupiter.api.Test;

class RepositoryRegistryTest {

    // Minimal types to satisfy generics
    abstract static class StubModel extends Model<StubModel, Id<StubModel>, GenericState> {
        protected StubModel(Builder<Id<StubModel>, ?, StubModel, GenericState> builder) {
            super(builder);
        }
    }

    abstract static class StubEntity extends Entity<StubEntity, Id<StubEntity>, GenericState> {
        protected StubEntity(Builder<Id<StubEntity>, ?, StubEntity, GenericState> builder) {
            super(builder);
        }
    }

    abstract static class OtherModel extends Model<OtherModel, Id<OtherModel>, GenericState> {
        protected OtherModel(Builder<Id<OtherModel>, ?, OtherModel, GenericState> builder) {
            super(builder);
        }
    }

    // Typed repository interfaces for mock compatibility with withModelRepository/withEntityRepository generics
    interface StubModelRepository extends Repository<StubModel> {}

    interface StubEntityRepository extends Repository<StubEntity> {}

    @Test
    void build_creates_empty_registry() {
        // WHEN
        var registry = repositoryRegistry().build();

        // THEN
        assertThat(registry.repositories).isEmpty();
    }

    @Test
    void withModelRepository_registers_and_retrieves() {
        // GIVEN
        var repo = mock(StubModelRepository.class);

        // WHEN
        var registry =
                repositoryRegistry().withModelRepository(StubModel.class, repo).build();

        // THEN
        assertThat(registry.repository(StubModel.class)).isSameAs(repo);
    }

    @Test
    void withEntityRepository_registers_and_retrieves() {
        // GIVEN
        var repo = mock(StubEntityRepository.class);

        // WHEN
        var registry = repositoryRegistry()
                .withEntityRepository(StubEntity.class, repo)
                .build();

        // THEN
        assertThat(registry.repository(StubEntity.class)).isSameAs(repo);
    }

    @Test
    void repository_returns_null_for_unregistered_type() {
        // GIVEN
        var repo = mock(StubModelRepository.class);
        var registry =
                repositoryRegistry().withModelRepository(StubModel.class, repo).build();

        // WHEN / THEN
        assertThat(registry.repository(OtherModel.class)).isNull();
    }

    @Test
    void multiple_repositories_registered() {
        // GIVEN
        var modelRepo = mock(StubModelRepository.class);
        var entityRepo = mock(StubEntityRepository.class);

        // WHEN
        var registry = repositoryRegistry()
                .withModelRepository(StubModel.class, modelRepo)
                .withEntityRepository(StubEntity.class, entityRepo)
                .build();

        // THEN
        assertThat(registry.repositories).hasSize(2);
        assertThat(registry.repository(StubModel.class)).isSameAs(modelRepo);
        assertThat(registry.repository(StubEntity.class)).isSameAs(entityRepo);
    }

    @Test
    void withModelRepository_rejects_null_class() {
        // GIVEN / WHEN / THEN
        var repo = mock(StubModelRepository.class);
        assertThatThrownBy(() -> repositoryRegistry().withModelRepository(null, repo))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modelClass cannot be null");
    }

    @Test
    void withModelRepository_rejects_null_repository() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> repositoryRegistry().withModelRepository(StubModel.class, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("repository cannot be null");
    }

    @Test
    void withEntityRepository_rejects_null_class() {
        // GIVEN / WHEN / THEN
        var repo = mock(StubEntityRepository.class);
        assertThatThrownBy(() -> repositoryRegistry().withEntityRepository(null, repo))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entityClass cannot be null");
    }

    @Test
    void withEntityRepository_rejects_null_repository() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> repositoryRegistry().withEntityRepository(StubEntity.class, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("repository cannot be null");
    }

    @Test
    void repositories_map_is_immutable() {
        // GIVEN
        var repo = mock(StubModelRepository.class);
        var registry =
                repositoryRegistry().withModelRepository(StubModel.class, repo).build();

        // WHEN / THEN
        assertThatThrownBy(() -> registry.repositories.put(OtherModel.class, mock(StubModelRepository.class)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
