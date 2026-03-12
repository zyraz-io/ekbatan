package io.ekbatan.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdTest {

    // Dummy type for type-safe Id creation
    interface DummyModel extends Identifiable<Id<DummyModel>> {}

    interface OtherModel extends Identifiable<Id<OtherModel>> {}

    @Test
    void of_with_uuid_creates_id() {
        // GIVEN
        var uuid = UUID.randomUUID();

        // WHEN
        var id = Id.of(DummyModel.class, uuid);

        // THEN
        assertThat(id.getValue()).isEqualTo(uuid);

        // AND
        assertThat(id.getId()).isEqualTo(uuid);
    }

    @Test
    void of_with_string_creates_id() {
        // GIVEN
        var uuid = UUID.randomUUID();

        // WHEN
        var id = Id.of(DummyModel.class, uuid.toString());

        // THEN
        assertThat(id.getValue()).isEqualTo(uuid);
    }

    @Test
    void of_rejects_null_class() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> Id.of(null, UUID.randomUUID()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Identifiable class cannot be null");
    }

    @Test
    void random_creates_non_null_id() {
        // WHEN
        var id = Id.random(DummyModel.class);

        // THEN
        assertThat(id).isNotNull();

        // AND
        assertThat(id.getValue()).isNotNull();
    }

    @Test
    void random_creates_unique_ids() {
        // WHEN
        var id1 = Id.random(DummyModel.class);
        var id2 = Id.random(DummyModel.class);

        // THEN
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void random_rejects_null_class() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> Id.random(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Identifiable class cannot be null");
    }

    @Test
    void equals_same_uuid_same_type() {
        // GIVEN
        var uuid = UUID.randomUUID();
        var a = Id.of(DummyModel.class, uuid);
        var b = Id.of(DummyModel.class, uuid);

        // WHEN / THEN
        assertThat(a).isEqualTo(b);
    }

    @Test
    void equals_different_uuid() {
        // GIVEN
        var a = Id.random(DummyModel.class);
        var b = Id.random(DummyModel.class);

        // WHEN / THEN
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hashCode_consistent_with_equals() {
        // GIVEN
        var uuid = UUID.randomUUID();
        var a = Id.of(DummyModel.class, uuid);
        var b = Id.of(DummyModel.class, uuid);

        // WHEN / THEN
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void compareTo_delegates_to_uuid() {
        // GIVEN
        var uuid1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var uuid2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var id1 = Id.of(DummyModel.class, uuid1);
        var id2 = Id.of(DummyModel.class, uuid2);

        // WHEN / THEN
        assertThat(id1.compareTo(id2)).isLessThan(0);

        // AND
        assertThat(id2.compareTo(id1)).isGreaterThan(0);

        // AND
        assertThat(id1.compareTo(id1)).isZero();
    }

    @Test
    void toString_returns_uuid_string() {
        // GIVEN
        var uuid = UUID.randomUUID();
        var id = Id.of(DummyModel.class, uuid);

        // WHEN / THEN
        assertThat(id.toString()).isEqualTo(uuid.toString());
    }

    @Test
    void stringValue_returns_uuid_string() {
        // GIVEN
        var uuid = UUID.randomUUID();
        var id = Id.of(DummyModel.class, uuid);

        // WHEN / THEN
        assertThat(id.stringValue()).isEqualTo(uuid.toString());
    }

    @Test
    void getId_returns_same_as_getValue() {
        // GIVEN
        var id = Id.random(DummyModel.class);

        // WHEN / THEN
        assertThat(id.getId()).isEqualTo(id.getValue());
    }
}
