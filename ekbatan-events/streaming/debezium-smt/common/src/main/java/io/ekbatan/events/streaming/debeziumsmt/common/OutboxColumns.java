package io.ekbatan.events.streaming.debeziumsmt.common;

import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.UUID;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;

/**
 * Converts a Debezium change-event column into the canonical Java value the {@code ActionEvent}
 * wire schemas declare.
 *
 * <p>The same logical column reaches an SMT as a different Java type depending on the database,
 * the column's declared type and precision, and the connector's {@code time.precision.mode}. A
 * {@code BOOLEAN} column is a real boolean on PostgreSQL but {@code TINYINT(1)} on MySQL and
 * MariaDB, which Debezium surfaces as an {@code INT16}. A timestamp column arrives as epoch
 * millis, micros, nanos, an ISO-8601 string, or a {@link Date}, depending on the same three
 * factors.
 *
 * <p>These methods therefore branch on what the record itself says the column is - the Connect
 * schema's logical type name - and never on which database produced it. The SMT needs no dialect
 * configuration, and a deployment that switches databases keeps emitting identical bytes.
 *
 * <p>Anything not covered raises {@link DataException} naming the column, its Connect schema and
 * the runtime type, rather than being coerced on a guess. Kafka Connect surfaces that message to
 * operators; a wrong value silently on the wire would not be.
 */
public final class OutboxColumns {

    /** Debezium: epoch millis, {@code INT64}. MySQL {@code DATETIME(0..3)}. */
    static final String DEBEZIUM_TIMESTAMP = "io.debezium.time.Timestamp";

    /** Debezium: epoch micros, {@code INT64}. PostgreSQL {@code TIMESTAMP}, MySQL {@code DATETIME(4..6)}. */
    static final String DEBEZIUM_MICRO_TIMESTAMP = "io.debezium.time.MicroTimestamp";

    /** Debezium: epoch nanos, {@code INT64}. */
    static final String DEBEZIUM_NANO_TIMESTAMP = "io.debezium.time.NanoTimestamp";

    /** Debezium: ISO-8601 string with offset. MySQL {@code TIMESTAMP}, PostgreSQL {@code TIMESTAMPTZ}. */
    static final String DEBEZIUM_ZONED_TIMESTAMP = "io.debezium.time.ZonedTimestamp";

    /** Debezium: days since epoch, {@code INT32}. */
    static final String DEBEZIUM_DATE = "io.debezium.time.Date";

    /** Connect logical timestamp, a {@link Date}. Produced by {@code time.precision.mode=connect}. */
    static final String CONNECT_TIMESTAMP = "org.apache.kafka.connect.data.Timestamp";

    /** Connect logical date, a {@link Date}. Produced by {@code time.precision.mode=connect}. */
    static final String CONNECT_DATE = "org.apache.kafka.connect.data.Date";

    private static final long MICROS_PER_MILLI = 1_000L;
    private static final long NANOS_PER_MICRO = 1_000L;
    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long MICROS_PER_DAY = 86_400L * MICROS_PER_SECOND;
    private static final int UUID_BYTES = 16;

    private OutboxColumns() {}

    /**
     * Reads a text column. Returns {@code null} when the column is null, which callers must treat
     * as "leave the target field unset" rather than writing an empty string.
     *
     * <p>A 16-byte binary value is rendered as a canonical UUID string. This is a defensive path,
     * not the MariaDB one: measured against Debezium 3.5 in
     * {@code MariadbSmtIntegrationTest}, a native MariaDB {@code UUID} column - stored as
     * {@code BINARY(16)} - is emitted as a UUID <em>string</em>, so it takes the branch above.
     * The binary branch covers a column that genuinely reaches Connect as bytes, such as a raw
     * {@code BINARY(16)} under {@code binary.handling.mode=bytes}. It fails loudly on any other
     * length rather than guessing at a layout.
     */
    public static String text(Struct row, Field column) {
        final var value = row.get(column);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof ByteBuffer buffer) {
            final var bytes = new byte[buffer.remaining()];
            buffer.duplicate().get(bytes);
            return uuidText(bytes, column);
        }
        if (value instanceof byte[] bytes) {
            return uuidText(bytes, column);
        }
        throw unsupported(column, value, "a string");
    }

    /**
     * Reads a timestamp column and normalises it to epoch microseconds.
     *
     * <p>Microseconds rather than milliseconds because it is lossless for every form Debezium
     * produces here: PostgreSQL {@code TIMESTAMP} and MySQL {@code DATETIME(6)} are already
     * micros, so their emitted bytes do not change, while the coarser forms widen exactly.
     */
    public static long epochMicros(Struct row, Field column) {
        final var value = requireNonNull(row, column);
        final var logicalType = column.schema().name();
        if (logicalType != null) {
            switch (logicalType) {
                case DEBEZIUM_MICRO_TIMESTAMP:
                    return number(value, column).longValue();
                case DEBEZIUM_TIMESTAMP:
                    return Math.multiplyExact(number(value, column).longValue(), MICROS_PER_MILLI);
                case DEBEZIUM_NANO_TIMESTAMP:
                    return number(value, column).longValue() / NANOS_PER_MICRO;
                case DEBEZIUM_DATE:
                    return Math.multiplyExact(number(value, column).longValue(), MICROS_PER_DAY);
                case DEBEZIUM_ZONED_TIMESTAMP:
                    return isoMicros(value, column);
                case CONNECT_TIMESTAMP:
                case CONNECT_DATE:
                    return Math.multiplyExact(date(value, column).getTime(), MICROS_PER_MILLI);
                default:
                    break;
            }
        }
        // A bare INT64 carries no unit, so there is nothing to normalise it against. Guessing
        // would put millis and micros on the same wire field across two deployments.
        throw new DataException("Cannot convert column '" + column.name() + "' to a timestamp: Connect schema "
                + describe(column) + " carries no recognised temporal logical type. Supported: "
                + DEBEZIUM_MICRO_TIMESTAMP + ", " + DEBEZIUM_TIMESTAMP + ", " + DEBEZIUM_NANO_TIMESTAMP + ", "
                + DEBEZIUM_DATE + ", " + DEBEZIUM_ZONED_TIMESTAMP + ", " + CONNECT_TIMESTAMP + ", " + CONNECT_DATE
                + ".");
    }

    /**
     * Reads a boolean column.
     *
     * <p>PostgreSQL {@code BOOLEAN} arrives as a {@link Boolean}. MySQL and MariaDB have no
     * boolean type - {@code BOOLEAN} is an alias for {@code TINYINT(1)} - so Debezium sends an
     * {@code INT16}, which is why a numeric zero/non-zero form is accepted here.
     */
    public static boolean bool(Struct row, Field column) {
        final var value = requireNonNull(row, column);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.longValue() != 0L;
        }
        throw unsupported(column, value, "a boolean");
    }

    private static String uuidText(byte[] bytes, Field column) {
        if (bytes.length != UUID_BYTES) {
            throw new DataException("Cannot convert column '" + column.name() + "' to a string: expected "
                    + UUID_BYTES + " bytes for a UUID, got " + bytes.length + " (Connect schema " + describe(column)
                    + ").");
        }
        final var buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }

    private static long isoMicros(Object value, Field column) {
        if (!(value instanceof String text)) {
            throw unsupported(column, value, "an ISO-8601 timestamp string");
        }
        try {
            final var parsed = OffsetDateTime.parse(text);
            return Math.addExact(
                    Math.multiplyExact(parsed.toEpochSecond(), MICROS_PER_SECOND), parsed.getNano() / NANOS_PER_MICRO);
        } catch (DateTimeParseException e) {
            throw new DataException(
                    "Cannot parse column '" + column.name() + "' as an ISO-8601 timestamp: '" + text + "'.", e);
        }
    }

    private static Number number(Object value, Field column) {
        if (value instanceof Number number) {
            return number;
        }
        throw unsupported(column, value, "a number");
    }

    private static Date date(Object value, Field column) {
        if (value instanceof Date date) {
            return date;
        }
        throw unsupported(column, value, "a java.util.Date");
    }

    private static Object requireNonNull(Struct row, Field column) {
        final var value = row.get(column);
        if (value == null) {
            throw new DataException("Column '" + column.name() + "' is null but the ActionEvent schema declares it"
                    + " non-optional. The outbox row is malformed.");
        }
        return value;
    }

    private static DataException unsupported(Field column, Object value, String wanted) {
        return new DataException("Cannot convert column '" + column.name() + "' to " + wanted + ": Connect schema "
                + describe(column) + " produced a " + value.getClass().getName() + ".");
    }

    private static String describe(Field column) {
        final var schema = column.schema();
        return schema.name() != null ? schema.type() + "/" + schema.name() : String.valueOf(schema.type());
    }
}
