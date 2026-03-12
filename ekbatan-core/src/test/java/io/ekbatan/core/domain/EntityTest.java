package io.ekbatan.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EntityTest {

    // --- Minimal test entity ---

    static class SampleEntity extends Entity<SampleEntity, Id<SampleEntity>, GenericState> {
        SampleEntity(Builder builder) {
            super(builder);
        }

        @Override
        public Builder copy() {
            return Builder.sampleEntity().copyBase(this);
        }

        static class Builder extends Entity.Builder<Id<SampleEntity>, Builder, SampleEntity, GenericState> {
            static Builder sampleEntity() {
                return new Builder();
            }

            @Override
            public SampleEntity build() {
                return new SampleEntity(this);
            }
        }
    }

    private SampleEntity createSample() {
        return SampleEntity.Builder.sampleEntity()
                .id(Id.random(SampleEntity.class))
                .state(GenericState.ACTIVE)
                .withInitialVersion()
                .build();
    }

    // --- Builder tests ---

    @Test
    void withInitialVersion_sets_version_to_1() {
        // WHEN
        var entity = createSample();

        // THEN
        assertThat(entity.version).isEqualTo(1L);
    }

    @Test
    void increaseVersion_increments_version() {
        // GIVEN
        var entity = createSample();

        // WHEN
        var updated = entity.copy().increaseVersion().build();

        // THEN
        assertThat(updated.version).isEqualTo(2L);
    }

    @Test
    void version_sets_explicit_version() {
        // WHEN
        var entity = SampleEntity.Builder.sampleEntity()
                .id(Id.random(SampleEntity.class))
                .state(GenericState.ACTIVE)
                .version(5L)
                .build();

        // THEN
        assertThat(entity.version).isEqualTo(5L);
    }

    @Test
    void version_rejects_zero() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> SampleEntity.Builder.sampleEntity()
                        .id(Id.random(SampleEntity.class))
                        .state(GenericState.ACTIVE)
                        .version(0L)
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void version_rejects_negative() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> SampleEntity.Builder.sampleEntity()
                        .id(Id.random(SampleEntity.class))
                        .state(GenericState.ACTIVE)
                        .version(-1L)
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Validation tests ---

    @Test
    void build_rejects_null_id() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> SampleEntity.Builder.sampleEntity()
                        .state(GenericState.ACTIVE)
                        .withInitialVersion()
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("id cannot be null");
    }

    @Test
    void build_rejects_null_state() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> SampleEntity.Builder.sampleEntity()
                        .id(Id.random(SampleEntity.class))
                        .withInitialVersion()
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("state cannot be null");
    }

    @Test
    void build_rejects_null_version() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> SampleEntity.Builder.sampleEntity()
                        .id(Id.random(SampleEntity.class))
                        .state(GenericState.ACTIVE)
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("version cannot be null");
    }

    // --- copyBase tests ---

    @Test
    void copyBase_copies_all_fields() {
        // GIVEN
        var original = SampleEntity.Builder.sampleEntity()
                .id(Id.random(SampleEntity.class))
                .state(GenericState.ACTIVE)
                .version(3L)
                .build();

        // WHEN
        var copy = original.copy().build();

        // THEN
        assertThat(copy.id).isEqualTo(original.id);
        assertThat(copy.state).isEqualTo(original.state);
        assertThat(copy.version).isEqualTo(original.version);
    }

    @Test
    void copyBase_allows_field_override() {
        // GIVEN
        var original = createSample();

        // WHEN
        var updated =
                original.copy().state(GenericState.DELETED).increaseVersion().build();

        // THEN
        assertThat(updated.id).isEqualTo(original.id);
        assertThat(updated.state).isEqualTo(GenericState.DELETED);
        assertThat(updated.version).isEqualTo(2L);
    }

    // --- Entity equals/hashCode ---

    @Test
    void equals_same_fields() {
        // GIVEN
        var id = Id.random(SampleEntity.class);
        var a = SampleEntity.Builder.sampleEntity()
                .id(id)
                .state(GenericState.ACTIVE)
                .withInitialVersion()
                .build();
        var b = SampleEntity.Builder.sampleEntity()
                .id(id)
                .state(GenericState.ACTIVE)
                .withInitialVersion()
                .build();

        // WHEN / THEN
        assertThat(a).isEqualTo(b);
    }

    @Test
    void not_equal_different_id() {
        // GIVEN
        var a = createSample();
        var b = createSample();

        // WHEN / THEN
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void not_equal_different_state() {
        // GIVEN
        var id = Id.random(SampleEntity.class);
        var a = SampleEntity.Builder.sampleEntity()
                .id(id)
                .state(GenericState.ACTIVE)
                .withInitialVersion()
                .build();
        var b = SampleEntity.Builder.sampleEntity()
                .id(id)
                .state(GenericState.DELETED)
                .withInitialVersion()
                .build();

        // WHEN / THEN
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void not_equal_different_version() {
        // GIVEN
        var id = Id.random(SampleEntity.class);
        var a = SampleEntity.Builder.sampleEntity()
                .id(id)
                .state(GenericState.ACTIVE)
                .withInitialVersion()
                .build();
        var b = SampleEntity.Builder.sampleEntity()
                .id(id)
                .state(GenericState.ACTIVE)
                .version(2L)
                .build();

        // WHEN / THEN
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hashCode_based_on_id() {
        // GIVEN
        var id = Id.random(SampleEntity.class);
        var a = SampleEntity.Builder.sampleEntity()
                .id(id)
                .state(GenericState.ACTIVE)
                .withInitialVersion()
                .build();
        var b = SampleEntity.Builder.sampleEntity()
                .id(id)
                .state(GenericState.ACTIVE)
                .withInitialVersion()
                .build();

        // WHEN / THEN
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // --- Persistable ---

    @Test
    void isModel_returns_false() {
        // GIVEN / WHEN / THEN
        assertThat(createSample().isModel()).isFalse();
    }

    @Test
    void nextVersion_returns_copy_with_incremented_version() {
        // GIVEN
        var entity = createSample();

        // WHEN
        SampleEntity next = entity.nextVersion();

        // THEN
        assertThat(next.version).isEqualTo(2L);

        // AND
        assertThat(next.id).isEqualTo(entity.id);
    }
}
