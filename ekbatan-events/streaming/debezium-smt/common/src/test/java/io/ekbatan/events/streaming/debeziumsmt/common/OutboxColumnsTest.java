package io.ekbatan.events.streaming.debeziumsmt.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The conversion table that lets one code path serve PostgreSQL, MySQL and MariaDB.
 *
 * <p>Every case here is a form Debezium genuinely produces for the {@code eventlog.events}
 * columns. The previous implementation copied the raw Connect value straight into the encoder, so
 * the non-PostgreSQL rows below either threw on every record or silently wrote the right number
 * in the wrong unit.
 */
class OutboxColumnsTest {

    private static final String COLUMN = "c";

    @Nested
    class Booleans {

        // PostgreSQL has a real BOOLEAN. MySQL and MariaDB do not - BOOLEAN is an alias for
        // TINYINT(1) - so Debezium sends INT16. Handing that Short to protobuf's setField threw
        // IllegalArgumentException on every row of a stock MySQL deployment.

        @Test
        void postgres_sends_a_real_boolean() {
            assertThat(bool(Schema.BOOLEAN_SCHEMA, true)).isTrue();
            assertThat(bool(Schema.BOOLEAN_SCHEMA, false)).isFalse();
        }

        @Test
        void mysql_and_mariadb_send_tinyint_one_as_int16() {
            assertThat(bool(Schema.INT16_SCHEMA, (short) 1)).isTrue();
            assertThat(bool(Schema.INT16_SCHEMA, (short) 0)).isFalse();
        }

        @Test
        void any_other_integer_width_is_accepted_too() {
            assertThat(bool(Schema.INT8_SCHEMA, (byte) 1)).isTrue();
            assertThat(bool(Schema.INT32_SCHEMA, 1)).isTrue();
            assertThat(bool(Schema.INT64_SCHEMA, 1L)).isTrue();
            assertThat(bool(Schema.INT32_SCHEMA, 0)).isFalse();
        }

        @Test
        void a_string_is_refused_rather_than_guessed_at() {
            assertThatThrownBy(() -> bool(Schema.STRING_SCHEMA, "true"))
                    .isInstanceOf(DataException.class)
                    .hasMessageContaining(COLUMN)
                    .hasMessageContaining("boolean");
        }

        @Test
        void null_names_the_column() {
            assertThatThrownBy(() -> bool(Schema.OPTIONAL_BOOLEAN_SCHEMA, null))
                    .isInstanceOf(DataException.class)
                    .hasMessageContaining(COLUMN);
        }
    }

    @Nested
    class Timestamps {

        // One instant, expressed the five ways Debezium can express it. Which one a deployment
        // gets depends on the database, the column's precision and time.precision.mode - so
        // before normalisation, two deployments of the same framework put different units on the
        // same wire field. Chosen with exact millisecond precision so every form is lossless.
        private static final Instant INSTANT = Instant.parse("2026-07-31T10:15:30.123Z");
        private static final long MICROS = INSTANT.getEpochSecond() * 1_000_000L + INSTANT.getNano() / 1_000L;

        @Test
        void postgres_timestamp_and_mysql_datetime6_are_micros() {
            assertThat(micros(debezium("MicroTimestamp", Schema.Type.INT64), MICROS))
                    .isEqualTo(MICROS);
        }

        @Test
        void mysql_datetime3_is_millis() {
            assertThat(micros(debezium("Timestamp", Schema.Type.INT64), INSTANT.toEpochMilli()))
                    .isEqualTo(MICROS);
        }

        @Test
        void nanos_are_narrowed() {
            assertThat(micros(debezium("NanoTimestamp", Schema.Type.INT64), MICROS * 1_000L))
                    .isEqualTo(MICROS);
        }

        @Test
        void mysql_timestamp_and_postgres_timestamptz_are_iso_strings() {
            assertThat(micros(debezium("ZonedTimestamp", Schema.Type.STRING), INSTANT.toString()))
                    .isEqualTo(MICROS);
        }

        @Test
        void time_precision_mode_connect_sends_a_java_util_date() {
            assertThat(micros(org.apache.kafka.connect.data.Timestamp.SCHEMA, Date.from(INSTANT)))
                    .isEqualTo(MICROS);
        }

        @Test
        void debezium_date_is_days_since_epoch() {
            assertThat(micros(debezium("Date", Schema.Type.INT32), 1)).isEqualTo(86_400L * 1_000_000L);
        }

        /**
         * The point of the exercise: every form above lands on one number, so switching database
         * does not change the bytes a consumer sees.
         */
        @Test
        void every_form_agrees_on_the_same_instant() {
            var postgres = micros(debezium("MicroTimestamp", Schema.Type.INT64), MICROS);
            var mysqlDatetime3 = micros(debezium("Timestamp", Schema.Type.INT64), INSTANT.toEpochMilli());
            var nanos = micros(debezium("NanoTimestamp", Schema.Type.INT64), MICROS * 1_000L);
            var iso = micros(debezium("ZonedTimestamp", Schema.Type.STRING), INSTANT.toString());
            var connect = micros(org.apache.kafka.connect.data.Timestamp.SCHEMA, Date.from(INSTANT));

            assertThat(mysqlDatetime3).isEqualTo(postgres);
            assertThat(nanos).isEqualTo(postgres);
            assertThat(iso).isEqualTo(postgres);
            assertThat(connect).isEqualTo(postgres);
        }

        // A bare INT64 carries no unit. Guessing is exactly how millis and micros could reach the
        // same wire field from two deployments, so it is refused.
        @Test
        void an_unlabelled_int64_is_refused_rather_than_assumed_to_be_micros() {
            assertThatThrownBy(() -> micros(Schema.INT64_SCHEMA, MICROS))
                    .isInstanceOf(DataException.class)
                    .hasMessageContaining(COLUMN)
                    .hasMessageContaining("MicroTimestamp");
        }

        @Test
        void an_unparseable_iso_string_names_the_column_and_the_value() {
            assertThatThrownBy(() -> micros(debezium("ZonedTimestamp", Schema.Type.STRING), "not-a-timestamp"))
                    .isInstanceOf(DataException.class)
                    .hasMessageContaining(COLUMN)
                    .hasMessageContaining("not-a-timestamp");
        }

        @Test
        void null_names_the_column() {
            assertThatThrownBy(() -> micros(
                            new SchemaBuilder(Schema.Type.INT64)
                                    .name("io.debezium.time.MicroTimestamp")
                                    .optional()
                                    .build(),
                            null))
                    .isInstanceOf(DataException.class)
                    .hasMessageContaining(COLUMN);
        }
    }

    @Nested
    class Text {

        @Test
        void a_string_passes_through() {
            assertThat(text(Schema.STRING_SCHEMA, "wallet-1")).isEqualTo("wallet-1");
        }

        @Test
        void null_is_returned_so_the_caller_can_leave_the_field_unset() {
            assertThat(text(Schema.OPTIONAL_STRING_SCHEMA, null)).isNull();
        }

        // Measured in MariadbSmtIntegrationTest: a native MariaDB UUID column reaches Connect as
        // a UUID *string*, not as bytes, so this is not the MariaDB shape. It covers a column that
        // genuinely arrives binary - a raw BINARY(16) under binary.handling.mode=bytes - and stays
        // strict about length rather than accommodating some other layout.
        @Test
        void sixteen_bytes_render_as_a_canonical_uuid() {
            var uuid = UUID.fromString("0198f4a2-1c3d-7e4f-8a9b-0c1d2e3f4a5b");
            var bytes = ByteBuffer.allocate(16)
                    .putLong(uuid.getMostSignificantBits())
                    .putLong(uuid.getLeastSignificantBits())
                    .array();

            assertThat(text(Schema.BYTES_SCHEMA, bytes)).isEqualTo(uuid.toString());
            assertThat(text(Schema.BYTES_SCHEMA, ByteBuffer.wrap(bytes))).isEqualTo(uuid.toString());
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 15, 17, 32})
        void any_other_byte_length_is_refused_rather_than_guessed_at(int length) {
            assertThatThrownBy(() -> text(Schema.BYTES_SCHEMA, new byte[length]))
                    .isInstanceOf(DataException.class)
                    .hasMessageContaining(COLUMN);
        }

        @Test
        void a_number_is_refused() {
            assertThatThrownBy(() -> text(Schema.INT32_SCHEMA, 7))
                    .isInstanceOf(DataException.class)
                    .hasMessageContaining(COLUMN);
        }
    }

    private static boolean bool(Schema columnSchema, Object value) {
        var row = row(columnSchema, value);
        return OutboxColumns.bool(row, row.schema().field(COLUMN));
    }

    private static long micros(Schema columnSchema, Object value) {
        var row = row(columnSchema, value);
        return OutboxColumns.epochMicros(row, row.schema().field(COLUMN));
    }

    private static String text(Schema columnSchema, Object value) {
        var row = row(columnSchema, value);
        return OutboxColumns.text(row, row.schema().field(COLUMN));
    }

    private static Schema debezium(String simpleName, Schema.Type type) {
        return new SchemaBuilder(type).name("io.debezium.time." + simpleName).build();
    }

    private static Struct row(Schema columnSchema, Object value) {
        return new Struct(SchemaBuilder.struct().field(COLUMN, columnSchema).build()).put(COLUMN, value);
    }
}
