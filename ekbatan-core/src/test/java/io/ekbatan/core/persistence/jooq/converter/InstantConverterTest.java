package io.ekbatan.core.persistence.jooq.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class InstantConverterTest {

    private final InstantConverter converter = new InstantConverter();

    @Test
    void from_converts_local_date_time_to_instant_in_utc() {
        // GIVEN
        var ldt = LocalDateTime.of(2025, 6, 15, 10, 30, 0);

        // WHEN / THEN
        assertThat(converter.from(ldt)).isEqualTo(Instant.parse("2025-06-15T10:30:00Z"));
    }

    @Test
    void from_returns_null_for_null() {
        // GIVEN / WHEN / THEN
        assertThat(converter.from(null)).isNull();
    }

    @Test
    void to_converts_instant_to_local_date_time_in_utc() {
        // GIVEN
        var instant = Instant.parse("2025-06-15T10:30:00Z");

        // WHEN / THEN
        assertThat(converter.to(instant)).isEqualTo(LocalDateTime.of(2025, 6, 15, 10, 30, 0));
    }

    @Test
    void to_returns_null_for_null() {
        // GIVEN / WHEN / THEN
        assertThat(converter.to(null)).isNull();
    }

    @Test
    void round_trip_preserves_value() {
        // GIVEN
        var instant = Instant.parse("2025-01-01T12:00:00Z");

        // WHEN / THEN
        assertThat(converter.from(converter.to(instant))).isEqualTo(instant);
    }

    @Test
    void round_trip_preserves_value_with_seconds() {
        // GIVEN
        var instant = Instant.parse("2025-12-31T23:59:59Z");

        // WHEN / THEN
        assertThat(converter.from(converter.to(instant))).isEqualTo(instant);
    }

    @Test
    void fromType_returns_local_date_time() {
        // GIVEN / WHEN / THEN
        assertThat(converter.fromType()).isEqualTo(LocalDateTime.class);
    }

    @Test
    void toType_returns_instant() {
        // GIVEN / WHEN / THEN
        assertThat(converter.toType()).isEqualTo(Instant.class);
    }
}
