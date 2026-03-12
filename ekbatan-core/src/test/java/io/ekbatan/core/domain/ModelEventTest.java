package io.ekbatan.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModelEventTest {

    static class TestEvent extends ModelEvent<String> {
        TestEvent(String modelId) {
            super(modelId, String.class);
        }
    }

    static class DetailedEvent extends ModelEvent<Object> {
        public final String detail;

        DetailedEvent(String modelId, String detail) {
            super(modelId, Object.class);
            this.detail = detail;
        }
    }

    @Test
    void constructor_sets_modelId_and_modelName() {
        // WHEN
        var event = new TestEvent("model-123");

        // THEN
        assertThat(event.modelId).isEqualTo("model-123");

        // AND
        assertThat(event.modelName).isEqualTo("String");
    }

    @Test
    void modelName_is_class_simple_name() {
        // WHEN
        var event = new DetailedEvent("id-1", "some detail");

        // THEN
        assertThat(event.modelName).isEqualTo("Object");

        // AND
        assertThat(event.detail).isEqualTo("some detail");
    }

    @Test
    void constructor_rejects_null_modelId() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> new TestEvent(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modelId cannot be null or blank");
    }

    @Test
    void constructor_rejects_blank_modelId() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> new TestEvent(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelId cannot be null or blank");
    }

    @Test
    void constructor_rejects_whitespace_modelId() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> new TestEvent("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelId cannot be null or blank");
    }

    @Test
    void constructor_rejects_null_modelClass() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> new ModelEvent<Object>("id", null) {})
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modelClass cannot be null");
    }

    @Test
    void is_serializable() {
        // GIVEN
        var event = new TestEvent("id-1");

        // WHEN / THEN
        assertThat(event).isInstanceOf(java.io.Serializable.class);
    }
}
