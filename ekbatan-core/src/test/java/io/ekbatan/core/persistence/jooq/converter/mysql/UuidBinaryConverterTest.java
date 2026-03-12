package io.ekbatan.core.persistence.jooq.converter.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidBinaryConverterTest {

    private final UuidBinaryConverter converter = new UuidBinaryConverter();

    @Test
    void from_converts_bytes_to_uuid() {
        // GIVEN
        var uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        var bytes = converter.to(uuid);

        // WHEN / THEN
        assertThat(converter.from(bytes)).isEqualTo(uuid);
    }

    @Test
    void from_returns_null_for_null() {
        // GIVEN / WHEN / THEN
        assertThat(converter.from(null)).isNull();
    }

    @Test
    void to_converts_uuid_to_16_bytes() {
        // GIVEN
        var uuid = UUID.randomUUID();

        // WHEN
        var bytes = converter.to(uuid);

        // THEN
        assertThat(bytes).hasSize(16);
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
    void round_trip_preserves_well_known_uuid() {
        // GIVEN
        var uuid = UUID.fromString("00000000-0000-0000-0000-000000000000");

        // WHEN / THEN
        assertThat(converter.from(converter.to(uuid))).isEqualTo(uuid);
    }

    @Test
    void fromType_returns_byte_array() {
        // GIVEN / WHEN / THEN
        assertThat(converter.fromType()).isEqualTo(byte[].class);
    }

    @Test
    void toType_returns_uuid() {
        // GIVEN / WHEN / THEN
        assertThat(converter.toType()).isEqualTo(UUID.class);
    }
}
