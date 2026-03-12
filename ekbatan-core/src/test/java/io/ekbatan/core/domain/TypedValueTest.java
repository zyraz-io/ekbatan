package io.ekbatan.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TypedValueTest {

    static class StringTypedValue extends TypedValue<String> {
        StringTypedValue(String value) {
            super(value);
        }
    }

    static class IntTypedValue extends TypedValue<Integer> {
        IntTypedValue(Integer value) {
            super(value);
        }
    }

    @Test
    void getValue_returns_wrapped_value() {
        // GIVEN
        var micro = new StringTypedValue("hello");

        // WHEN / THEN
        assertThat(micro.getValue()).isEqualTo("hello");
    }

    @Test
    void constructor_rejects_null() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> new StringTypedValue(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value cannot be null");
    }

    @Test
    void equals_same_type_same_value() {
        // GIVEN
        var a = new StringTypedValue("x");
        var b = new StringTypedValue("x");

        // WHEN / THEN
        assertThat(a).isEqualTo(b);
    }

    @Test
    void equals_same_type_different_value() {
        // GIVEN
        var a = new StringTypedValue("x");
        var b = new StringTypedValue("y");

        // WHEN / THEN
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equals_different_type_same_value_not_equal() {
        // GIVEN
        var stringMicro = new StringTypedValue("42");
        var intMicro = new IntTypedValue(42);

        // WHEN / THEN
        assertThat(stringMicro).isNotEqualTo(intMicro);
    }

    @Test
    void equals_null() {
        // GIVEN / WHEN / THEN
        assertThat(new StringTypedValue("x")).isNotEqualTo(null);
    }

    @Test
    void equals_same_instance() {
        // GIVEN
        var micro = new StringTypedValue("x");

        // WHEN / THEN
        assertThat(micro).isEqualTo(micro);
    }

    @Test
    void hashCode_same_for_equal_values() {
        // GIVEN
        var a = new StringTypedValue("x");
        var b = new StringTypedValue("x");

        // WHEN / THEN
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void hashCode_different_for_different_values() {
        // GIVEN
        var a = new StringTypedValue("x");
        var b = new StringTypedValue("y");

        // WHEN / THEN
        assertThat(a.hashCode()).isNotEqualTo(b.hashCode());
    }

    @Test
    void toString_includes_class_name_and_value() {
        // GIVEN
        var micro = new StringTypedValue("hello");

        // WHEN / THEN
        assertThat(micro.toString()).isEqualTo("StringTypedValue{hello}");
    }
}
