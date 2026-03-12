package io.ekbatan.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelTest {

    // --- Minimal test model ---

    static class SampleEvent extends ModelEvent<SampleModel> {
        SampleEvent(Id<SampleModel> id) {
            super(id.getValue().toString(), SampleModel.class);
        }
    }

    static class SampleModel extends Model<SampleModel, Id<SampleModel>, GenericState> {
        SampleModel(Builder builder) {
            super(builder);
        }

        @Override
        public Builder copy() {
            return Builder.sampleModel().copyBase(this);
        }

        static class Builder extends Model.Builder<Id<SampleModel>, Builder, SampleModel, GenericState> {
            static Builder sampleModel() {
                return new Builder();
            }

            @Override
            public SampleModel build() {
                return new SampleModel(this);
            }
        }
    }

    private SampleModel createSample() {
        return SampleModel.Builder.sampleModel()
                .id(Id.random(SampleModel.class))
                .state(GenericState.ACTIVE)
                .createdDate(Instant.parse("2025-01-01T00:00:00Z"))
                .withInitialVersion()
                .build();
    }

    // --- Builder tests ---

    @Test
    void withInitialVersion_sets_version_to_1() {
        // WHEN
        var model = createSample();

        // THEN
        assertThat(model.version).isEqualTo(1L);
    }

    @Test
    void increaseVersion_increments_version() {
        // GIVEN
        var model = createSample();

        // WHEN
        var updated = model.copy().increaseVersion().build();

        // THEN
        assertThat(updated.version).isEqualTo(2L);
    }

    @Test
    void version_sets_explicit_version() {
        // WHEN
        var model = SampleModel.Builder.sampleModel()
                .id(Id.random(SampleModel.class))
                .state(GenericState.ACTIVE)
                .createdDate(Instant.now())
                .version(5L)
                .build();

        // THEN
        assertThat(model.version).isEqualTo(5L);
    }

    @Test
    void version_rejects_zero() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> SampleModel.Builder.sampleModel()
                        .id(Id.random(SampleModel.class))
                        .state(GenericState.ACTIVE)
                        .createdDate(Instant.now())
                        .version(0L)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version must be greater than or equal to 1");
    }

    @Test
    void version_rejects_negative() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> SampleModel.Builder.sampleModel()
                        .id(Id.random(SampleModel.class))
                        .state(GenericState.ACTIVE)
                        .createdDate(Instant.now())
                        .version(-1L)
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withEvent_adds_event_to_list() {
        // GIVEN
        var id = Id.random(SampleModel.class);

        // WHEN
        var model = SampleModel.Builder.sampleModel()
                .id(id)
                .state(GenericState.ACTIVE)
                .createdDate(Instant.now())
                .withInitialVersion()
                .withEvent(new SampleEvent(id))
                .build();

        // THEN
        assertThat(model.events).hasSize(1);

        // AND
        assertThat(model.events.getFirst()).isInstanceOf(SampleEvent.class);
    }

    @Test
    void withEvent_accumulates_multiple_events() {
        // GIVEN
        var id = Id.random(SampleModel.class);

        // WHEN
        var model = SampleModel.Builder.sampleModel()
                .id(id)
                .state(GenericState.ACTIVE)
                .createdDate(Instant.now())
                .withInitialVersion()
                .withEvent(new SampleEvent(id))
                .withEvent(new SampleEvent(id))
                .build();

        // THEN
        assertThat(model.events).hasSize(2);
    }

    @Test
    void events_replaces_event_list() {
        // GIVEN
        var id = Id.random(SampleModel.class);
        var event = new SampleEvent(id);

        // WHEN
        var model = SampleModel.Builder.sampleModel()
                .id(id)
                .state(GenericState.ACTIVE)
                .createdDate(Instant.now())
                .withInitialVersion()
                .withEvent(new SampleEvent(id))
                .withEvent(new SampleEvent(id))
                .events(List.of(event))
                .build();

        // THEN
        assertThat(model.events).hasSize(1);

        // AND
        assertThat(model.events.getFirst()).isSameAs(event);
    }

    @Test
    void events_list_is_immutable() {
        // GIVEN
        var model = createSample();

        // WHEN / THEN
        assertThatThrownBy(() -> model.events.add(new SampleEvent(model.id)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void builder_without_events_has_empty_list() {
        // WHEN
        var model = createSample();

        // THEN
        assertThat(model.events).isEmpty();
    }

    @Test
    void createdDate_truncated_to_microseconds() {
        // GIVEN
        var nanos = Instant.parse("2025-01-01T00:00:00.123456789Z");

        // WHEN
        var model = SampleModel.Builder.sampleModel()
                .id(Id.random(SampleModel.class))
                .state(GenericState.ACTIVE)
                .createdDate(nanos)
                .withInitialVersion()
                .build();

        // THEN
        assertThat(model.createdDate).isEqualTo(Instant.parse("2025-01-01T00:00:00.123456Z"));
    }

    @Test
    void updatedDate_defaults_to_createdDate_when_null() {
        // WHEN
        var model = createSample();

        // THEN
        assertThat(model.updatedDate).isEqualTo(model.createdDate);
    }

    @Test
    void updatedDate_set_explicitly() {
        // GIVEN
        var created = Instant.parse("2025-01-01T00:00:00Z");
        var updated = Instant.parse("2025-06-01T00:00:00Z");

        // WHEN
        var model = SampleModel.Builder.sampleModel()
                .id(Id.random(SampleModel.class))
                .state(GenericState.ACTIVE)
                .createdDate(created)
                .updatedDate(updated)
                .withInitialVersion()
                .build();

        // THEN
        assertThat(model.updatedDate).isEqualTo(updated);
    }

    @Test
    void updatedDate_truncated_to_microseconds() {
        // GIVEN
        var nanos = Instant.parse("2025-06-01T00:00:00.999999999Z");

        // WHEN
        var model = SampleModel.Builder.sampleModel()
                .id(Id.random(SampleModel.class))
                .state(GenericState.ACTIVE)
                .createdDate(Instant.now())
                .updatedDate(nanos)
                .withInitialVersion()
                .build();

        // THEN
        assertThat(model.updatedDate).isEqualTo(Instant.parse("2025-06-01T00:00:00.999999Z"));
    }

    // --- Validation tests ---

    @Test
    void build_rejects_null_id() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> SampleModel.Builder.sampleModel()
                        .state(GenericState.ACTIVE)
                        .createdDate(Instant.now())
                        .withInitialVersion()
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("id cannot be null");
    }

    @Test
    void build_rejects_null_state() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> SampleModel.Builder.sampleModel()
                        .id(Id.random(SampleModel.class))
                        .createdDate(Instant.now())
                        .withInitialVersion()
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("state cannot be null");
    }

    @Test
    void build_rejects_null_createdDate() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> SampleModel.Builder.sampleModel()
                        .id(Id.random(SampleModel.class))
                        .state(GenericState.ACTIVE)
                        .withInitialVersion()
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("createdDate cannot be null");
    }

    @Test
    void build_rejects_null_version() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> SampleModel.Builder.sampleModel()
                        .id(Id.random(SampleModel.class))
                        .state(GenericState.ACTIVE)
                        .createdDate(Instant.now())
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("version cannot be null");
    }

    // --- copyBase tests ---

    @Test
    void copyBase_copies_all_fields() {
        // GIVEN
        var id = Id.random(SampleModel.class);
        var event = new SampleEvent(id);
        var original = SampleModel.Builder.sampleModel()
                .id(id)
                .state(GenericState.ACTIVE)
                .createdDate(Instant.parse("2025-01-01T00:00:00Z"))
                .updatedDate(Instant.parse("2025-06-01T00:00:00Z"))
                .version(3L)
                .withEvent(event)
                .build();

        // WHEN
        var copy = original.copy().build();

        // THEN
        assertThat(copy.id).isEqualTo(original.id);
        assertThat(copy.state).isEqualTo(original.state);
        assertThat(copy.createdDate).isEqualTo(original.createdDate);
        assertThat(copy.updatedDate).isEqualTo(original.updatedDate);
        assertThat(copy.version).isEqualTo(original.version);
        assertThat(copy.events).hasSize(1);
    }

    @Test
    void copyBase_events_are_independent_of_original() {
        // GIVEN
        var id = Id.random(SampleModel.class);
        var original = SampleModel.Builder.sampleModel()
                .id(id)
                .state(GenericState.ACTIVE)
                .createdDate(Instant.now())
                .withInitialVersion()
                .withEvent(new SampleEvent(id))
                .build();

        // WHEN
        var copy = original.copy().withEvent(new SampleEvent(id)).build();

        // THEN
        assertThat(original.events).hasSize(1);

        // AND
        assertThat(copy.events).hasSize(2);
    }

    // --- Model equals/hashCode ---

    @Test
    void equals_same_fields() {
        // GIVEN
        var id = Id.random(SampleModel.class);
        var created = Instant.parse("2025-01-01T00:00:00Z");
        var a = SampleModel.Builder.sampleModel()
                .id(id)
                .state(GenericState.ACTIVE)
                .createdDate(created)
                .withInitialVersion()
                .build();
        var b = SampleModel.Builder.sampleModel()
                .id(id)
                .state(GenericState.ACTIVE)
                .createdDate(created)
                .withInitialVersion()
                .build();

        // WHEN / THEN
        assertThat(a).isEqualTo(b);
    }

    @Test
    void equals_ignores_events() {
        // GIVEN
        var id = Id.random(SampleModel.class);
        var created = Instant.parse("2025-01-01T00:00:00Z");
        var withEvent = SampleModel.Builder.sampleModel()
                .id(id)
                .state(GenericState.ACTIVE)
                .createdDate(created)
                .withInitialVersion()
                .withEvent(new SampleEvent(id))
                .build();
        var withoutEvent = SampleModel.Builder.sampleModel()
                .id(id)
                .state(GenericState.ACTIVE)
                .createdDate(created)
                .withInitialVersion()
                .build();

        // WHEN / THEN
        assertThat(withEvent).isEqualTo(withoutEvent);
    }

    @Test
    void not_equal_different_id() {
        // GIVEN
        var created = Instant.parse("2025-01-01T00:00:00Z");
        var a = SampleModel.Builder.sampleModel()
                .id(Id.random(SampleModel.class))
                .state(GenericState.ACTIVE)
                .createdDate(created)
                .withInitialVersion()
                .build();
        var b = SampleModel.Builder.sampleModel()
                .id(Id.random(SampleModel.class))
                .state(GenericState.ACTIVE)
                .createdDate(created)
                .withInitialVersion()
                .build();

        // WHEN / THEN
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void not_equal_different_version() {
        // GIVEN
        var id = Id.random(SampleModel.class);
        var created = Instant.parse("2025-01-01T00:00:00Z");
        var v1 = SampleModel.Builder.sampleModel()
                .id(id)
                .state(GenericState.ACTIVE)
                .createdDate(created)
                .withInitialVersion()
                .build();
        var v2 = SampleModel.Builder.sampleModel()
                .id(id)
                .state(GenericState.ACTIVE)
                .createdDate(created)
                .version(2L)
                .build();

        // WHEN / THEN
        assertThat(v1).isNotEqualTo(v2);
    }

    @Test
    void hashCode_based_on_id() {
        // GIVEN
        var id = Id.random(SampleModel.class);
        var a = SampleModel.Builder.sampleModel()
                .id(id)
                .state(GenericState.ACTIVE)
                .createdDate(Instant.now())
                .withInitialVersion()
                .build();
        var b = SampleModel.Builder.sampleModel()
                .id(id)
                .state(GenericState.ACTIVE)
                .createdDate(Instant.now())
                .withInitialVersion()
                .build();

        // WHEN / THEN
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // --- Persistable ---

    @Test
    void isModel_returns_true() {
        // GIVEN / WHEN / THEN
        assertThat(createSample().isModel()).isTrue();
    }

    @Test
    void nextVersion_returns_copy_with_incremented_version() {
        // GIVEN
        var model = createSample();

        // WHEN
        SampleModel next = model.nextVersion();

        // THEN
        assertThat(next.version).isEqualTo(2L);

        // AND
        assertThat(next.id).isEqualTo(model.id);
    }
}
