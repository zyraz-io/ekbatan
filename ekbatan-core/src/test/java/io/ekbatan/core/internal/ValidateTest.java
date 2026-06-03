package io.ekbatan.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ValidateTest {

    @Test
    void notNull_returns_the_value_when_non_null() {
        var value = "hello";
        assertThat(Validate.notNull(value, "msg")).isSameAs(value);
    }

    @Test
    void notNull_throws_NPE_with_message_when_null() {
        assertThatThrownBy(() -> Validate.notNull(null, "must not be null"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("must not be null");
    }

    @Test
    void isTrue_returns_normally_when_true() {
        Validate.isTrue(true, "should not throw");
    }

    @Test
    void isTrue_throws_IAE_with_message_when_false() {
        assertThatThrownBy(() -> Validate.isTrue(false, "expected condition"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expected condition");
    }

    @Test
    void notBlank_returns_the_value_when_populated() {
        assertThat(Validate.notBlank("abc", "msg")).isEqualTo("abc");
    }

    @Test
    void notBlank_throws_NPE_when_null() {
        assertThatThrownBy(() -> Validate.notBlank(null, "name required"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("name required");
    }

    @Test
    void notBlank_throws_IAE_when_empty() {
        assertThatThrownBy(() -> Validate.notBlank("", "name required"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name required");
    }

    @Test
    void notBlank_throws_IAE_when_whitespace_only() {
        assertThatThrownBy(() -> Validate.notBlank("   \t\n", "name required"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name required");
    }

    @Test
    void inclusiveBetween_returns_normally_at_endpoints_and_inside() {
        Validate.inclusiveBetween(0, 10, 0, "msg");
        Validate.inclusiveBetween(0, 10, 5, "msg");
        Validate.inclusiveBetween(0, 10, 10, "msg");
    }

    @Test
    void inclusiveBetween_throws_IAE_below_range() {
        assertThatThrownBy(() -> Validate.inclusiveBetween(0, 10, -1, "out of range"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("out of range");
    }

    @Test
    void inclusiveBetween_throws_IAE_above_range() {
        assertThatThrownBy(() -> Validate.inclusiveBetween(0, 10, 11, "out of range"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("out of range");
    }

    @Test
    void notEmpty_returns_the_collection_when_populated() {
        var list = List.of("a");
        assertThat(Validate.notEmpty(list, "msg")).isSameAs(list);
    }

    @Test
    void notEmpty_throws_NPE_when_null() {
        assertThatThrownBy(() -> Validate.notEmpty(null, "collection required"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("collection required");
    }

    @Test
    void notEmpty_throws_IAE_when_empty() {
        assertThatThrownBy(() -> Validate.notEmpty(List.of(), "collection required"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("collection required");
    }

    @Test
    void isInstanceOf_returns_normally_for_matching_type() {
        Validate.isInstanceOf(String.class, "abc", "msg");
        Validate.isInstanceOf(Number.class, 42, "msg");
    }

    @Test
    void isInstanceOf_throws_IAE_for_mismatched_type() {
        assertThatThrownBy(() -> Validate.isInstanceOf(String.class, 42, "wrong type"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("wrong type");
    }

    @Test
    void isInstanceOf_throws_IAE_when_obj_is_null() {
        assertThatThrownBy(() -> Validate.isInstanceOf(String.class, null, "wrong type"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("wrong type");
    }

    @Test
    void isTrue_varargs_formats_message_when_false() {
        assertThatThrownBy(() -> Validate.isTrue(false, "configs.%s is required", "primary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("configs.primary is required");
    }

    @Test
    void notNull_varargs_formats_message_when_null() {
        assertThatThrownBy(() -> Validate.notNull(null, "missing %s field on %s", "id", "Wallet"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("missing id field on Wallet");
    }

    @Test
    void notBlank_varargs_formats_message_when_blank() {
        assertThatThrownBy(() -> Validate.notBlank("  ", "%s cannot be blank", "name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name cannot be blank");
    }

    @Test
    void notEmpty_varargs_formats_message_when_empty() {
        assertThatThrownBy(() -> Validate.notEmpty(List.of(), "%s collection cannot be empty", "jobs"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jobs collection cannot be empty");
    }

    @Test
    void inclusiveBetween_varargs_formats_message_when_out_of_range() {
        assertThatThrownBy(() -> Validate.inclusiveBetween(0, 10, 15, "%s out of range [%d, %d]", "value", 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("value out of range [0, 10]");
    }

    @Test
    void isInstanceOf_varargs_formats_message_when_mismatched() {
        assertThatThrownBy(() -> Validate.isInstanceOf(String.class, 42, "expected %s", "String"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expected String");
    }
}
