package io.ekbatan.core.persistence.jooq.converter.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidStringConverterTest {

    private final UuidStringConverter converter = new UuidStringConverter();

    @Test
    void from_converts_string_to_uuid() {
        // GIVEN / WHEN
        var result = converter.from("550e8400-e29b-41d4-a716-446655440000");

        // THEN
        assertThat(result).isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test
    void from_returns_null_for_null() {
        // GIVEN / WHEN / THEN
        assertThat(converter.from(null)).isNull();
    }

    @Test
    void to_converts_uuid_to_string() {
        // GIVEN
        var uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        // WHEN / THEN
        assertThat(converter.to(uuid)).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    void to_returns_null_for_null() {
        // GIVEN / WHEN / THEN
        assertThat(converter.to(null)).isNull();
    }

    @Test
    void round_trip_preserves_value() {
        // GIVEN
        var uuid = UUID.randomUUID();

        // WHEN / THEN
        assertThat(converter.from(converter.to(uuid))).isEqualTo(uuid);
    }

    @Test
    void fromType_returns_string() {
        // GIVEN / WHEN / THEN
        assertThat(converter.fromType()).isEqualTo(String.class);
    }

    @Test
    void toType_returns_uuid() {
        // GIVEN / WHEN / THEN
        assertThat(converter.toType()).isEqualTo(UUID.class);
    }
}
