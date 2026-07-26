package io.ekbatan.events.streaming.debeziumsmt.avro;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.connect.errors.DataException;

/**
 * Converts the outbox payload's JSON into an Avro record against a per-event-type schema.
 *
 * <p>Avro ships no equivalent of protobuf's {@code JsonFormat.parser()}. Its
 * {@code DecoderFactory.jsonDecoder} looks like one but reads Avro's <em>own</em> JSON encoding,
 * in which a union value must be tagged with its type - {@code {"amount":{"string":"77.10"}}} -
 * and never the plain {@code {"amount":"77.10"}} that Jackson writes. So this conversion has to
 * be done here.
 *
 * <p>The previous implementation guessed, using a test that could not fail. Jackson's
 * {@code asLong()} returns {@code 0} for the text {@code "ORDER-123"} rather than complaining, and
 * union resolution tried each branch until one did not throw - so it always took the first branch,
 * and a string field declared {@code ["null","long","string"]} silently reached Kafka as
 * {@code 0}.
 *
 * <p>The rule here instead:
 *
 * <ul>
 *   <li>The <b>schema decides the type</b>. The top-level schema is chosen by {@code event_type},
 *       and every field below it is declared, so nothing about them is inferred.
 *   <li>A conversion that does not fit <b>fails</b>, naming the field path - never a default value.
 *   <li>The value is consulted only to pick between branches the schema deliberately left open,
 *       and only when more than one remains. {@code ["null", T]} - which is simply how Avro spells
 *       "optional" - is decided by the schema alone.
 * </ul>
 */
final class JsonToAvro {

    private final Consumer<String> onWarning;

    JsonToAvro(Consumer<String> onWarning) {
        this.onWarning = onWarning;
    }

    /** Converts a whole payload document against its event type's schema. */
    GenericRecord convert(JsonNode json, Schema schema, String eventType) {
        if (!json.isObject()) {
            throw new DataException("Payload for " + eventType + " is not a JSON object but a " + json.getNodeType()
                    + "; the outbox payload must serialize to an object.");
        }
        return record(json, schema, eventType);
    }

    private GenericRecord record(JsonNode json, Schema schema, String path) {
        final var record = new GenericData.Record(schema);
        for (final var field : schema.getFields()) {
            final var node = json.get(field.name());
            final var fieldPath = path + "." + field.name();
            if (node == null) {
                // Absent from the JSON. A default makes it optional; without one the payload and
                // the schema disagree, and saying so beats writing a silent null.
                if (field.hasDefaultValue()) {
                    record.put(field.name(), GenericData.get().getDefaultValue(field));
                    continue;
                }
                throw new DataException(
                        "Missing field '" + fieldPath + "', which the schema declares" + " with no default value.");
            }
            record.put(field.name(), value(node, field.schema(), fieldPath));
        }
        warnAboutUnknownKeys(json, schema, path);
        return record;
    }

    private Object value(JsonNode node, Schema schema, String path) {
        if (schema.getType() == Schema.Type.UNION) {
            return union(node, schema, path);
        }
        if (node.isNull()) {
            throw new DataException("Field '" + path + "' is null but the schema declares " + schema.getType()
                    + " with no null" + " branch. Declare it as [\"null\", ...] if it is optional.");
        }
        final var logical = logicalValue(node, schema, path);
        if (logical != null) {
            return logical;
        }
        return switch (schema.getType()) {
            case STRING -> text(node, schema, path);
            case INT -> {
                requireNumber(node, schema, path);
                if (!node.canConvertToInt()) {
                    throw mismatch(path, schema, node, "a value that fits in an int");
                }
                yield node.intValue();
            }
            case LONG -> {
                requireNumber(node, schema, path);
                if (!node.canConvertToLong()) {
                    throw mismatch(path, schema, node, "a value that fits in a long");
                }
                yield node.longValue();
            }
            case FLOAT -> {
                requireNumber(node, schema, path);
                yield (float) node.doubleValue();
            }
            case DOUBLE -> {
                requireNumber(node, schema, path);
                yield node.doubleValue();
            }
            case BOOLEAN -> {
                if (!node.isBoolean()) {
                    throw mismatch(path, schema, node, "a boolean");
                }
                yield node.booleanValue();
            }
            case BYTES -> ByteBuffer.wrap(binary(node, schema, path));
            case FIXED -> {
                final var bytes = binary(node, schema, path);
                if (bytes.length != schema.getFixedSize()) {
                    throw new DataException("Field '" + path + "' needs exactly " + schema.getFixedSize()
                            + " bytes for fixed type " + schema.getFullName() + ", got " + bytes.length + ".");
                }
                yield new GenericData.Fixed(schema, bytes);
            }
            case ENUM -> {
                final var symbol = text(node, schema, path);
                if (!schema.getEnumSymbols().contains(symbol)) {
                    throw new DataException("Field '" + path + "' has value '" + symbol + "', which is not one of "
                            + schema.getEnumSymbols() + ".");
                }
                yield new GenericData.EnumSymbol(schema, symbol);
            }
            case RECORD -> {
                if (!node.isObject()) {
                    throw mismatch(path, schema, node, "an object");
                }
                yield record(node, schema, path);
            }
            case ARRAY -> {
                if (!node.isArray()) {
                    throw mismatch(path, schema, node, "an array");
                }
                final var list = new ArrayList<>(node.size());
                var index = 0;
                for (final var element : node) {
                    list.add(value(element, schema.getElementType(), path + "[" + index++ + "]"));
                }
                yield list;
            }
            case MAP -> {
                if (!node.isObject()) {
                    throw mismatch(path, schema, node, "an object");
                }
                final var map = new LinkedHashMap<String, Object>();
                for (final var entry : node.properties()) {
                    map.put(
                            entry.getKey(),
                            value(entry.getValue(), schema.getValueType(), path + "." + entry.getKey()));
                }
                yield map;
            }
            case NULL -> throw mismatch(path, schema, node, "null");
            default ->
                throw new DataException("Field '" + path + "' has unsupported Avro type " + schema.getType() + ".");
        };
    }

    /**
     * Picks a union branch.
     *
     * <p>{@code null} and the two-branch {@code ["null", T]} shape - which is all "optional" means
     * in Avro, and nearly every union in practice - are settled by the schema alone. Only a union
     * with several non-null branches needs the value, and then it is a choice among candidates
     * that actually convert, not a guess.
     */
    private Object union(JsonNode node, Schema union, String path) {
        final var branches = union.getTypes();
        if (node.isNull()) {
            for (final var branch : branches) {
                if (branch.getType() == Schema.Type.NULL) {
                    return null;
                }
            }
            throw new DataException("Field '" + path + "' is null but its union has no null branch: " + union + ".");
        }

        final var candidates = new ArrayList<Schema>();
        for (final var branch : branches) {
            if (branch.getType() != Schema.Type.NULL) {
                candidates.add(branch);
            }
        }
        if (candidates.size() == 1) {
            return value(node, candidates.getFirst(), path);
        }

        final var fitting = new ArrayList<Fit>();
        final var rejections = new ArrayList<String>();
        for (final var branch : candidates) {
            try {
                fitting.add(new Fit(
                        branch,
                        value(node, branch, path),
                        keysAccountedFor(node, branch),
                        fieldsLeftUnfilled(node, branch)));
            } catch (DataException e) {
                rejections.add(branch.getType() + ": " + e.getMessage());
            }
        }
        if (fitting.isEmpty()) {
            throw new DataException("Field '" + path + "' matches no branch of " + union + ". Tried - "
                    + String.join(" | ", rejections));
        }

        var best = fitting.getFirst();
        var tied = false;
        for (final var candidate : fitting.subList(1, fitting.size())) {
            final var comparison = candidate.compareTo(best);
            if (comparison > 0) {
                best = candidate;
                tied = false;
            } else if (comparison == 0) {
                tied = true;
            }
        }
        if (tied) {
            onWarning.accept("Field '" + path + "' fits more than one branch of " + union
                    + " equally well; taking the first declared. Make the branches distinguishable to remove the"
                    + " ambiguity.");
        }
        return best.converted;
    }

    /** A branch the value converted against, with the two numbers used to rank it. */
    private static final class Fit {
        private final Object converted;
        private final int keysAccountedFor;
        private final int fieldsLeftUnfilled;

        private Fit(Schema branch, Object converted, int keysAccountedFor, int fieldsLeftUnfilled) {
            this.converted = converted;
            this.keysAccountedFor = keysAccountedFor;
            this.fieldsLeftUnfilled = fieldsLeftUnfilled;
        }

        /** More of the JSON explained wins; then the tighter schema; then declaration order. */
        private int compareTo(Fit other) {
            if (keysAccountedFor != other.keysAccountedFor) {
                return Integer.compare(keysAccountedFor, other.keysAccountedFor);
            }
            return Integer.compare(other.fieldsLeftUnfilled, fieldsLeftUnfilled);
        }
    }

    private static int keysAccountedFor(JsonNode node, Schema branch) {
        if (!node.isObject()) {
            return 0;
        }
        if (branch.getType() == Schema.Type.MAP) {
            return node.size();
        }
        if (branch.getType() != Schema.Type.RECORD) {
            return 0;
        }
        var matched = 0;
        for (final var names = node.fieldNames(); names.hasNext(); ) {
            if (branch.getField(names.next()) != null) {
                matched++;
            }
        }
        return matched;
    }

    private static int fieldsLeftUnfilled(JsonNode node, Schema branch) {
        if (branch.getType() != Schema.Type.RECORD || !node.isObject()) {
            return 0;
        }
        var unfilled = 0;
        for (final var field : branch.getFields()) {
            if (node.get(field.name()) == null) {
                unfilled++;
            }
        }
        return unfilled;
    }

    private void warnAboutUnknownKeys(JsonNode json, Schema schema, String path) {
        final var unknown = new ArrayList<String>();
        for (final var names = json.fieldNames(); names.hasNext(); ) {
            final var name = names.next();
            if (schema.getField(name) == null) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            // Dropped rather than rejected, so adding a field to an event class cannot take the
            // pipeline down - but never silently, because "my new field is missing from Kafka" is
            // otherwise very hard to trace back to here.
            onWarning.accept("Payload field(s) " + unknown + " at '" + path + "' are not declared by schema "
                    + schema.getFullName() + " and are being dropped. The event class and the Avro schema have"
                    + " drifted apart.");
        }
    }

    /**
     * Handles the logical types the outbox payloads actually use. Returns {@code null} when the
     * schema carries none, so the caller falls through to the underlying type.
     */
    private Object logicalValue(JsonNode node, Schema schema, String path) {
        final var logical = schema.getLogicalType();
        if (logical == null) {
            return null;
        }
        if (logical instanceof LogicalTypes.Decimal decimal) {
            return decimal(node, schema, decimal, path);
        }
        return switch (logical.getName()) {
            case "timestamp-millis" -> epoch(node, path, 1_000L);
            case "timestamp-micros" -> epoch(node, path, 1_000_000L);
            // Avro 1.12 added timestamp-nanos. Without a case here it fell to `default -> null`,
            // which means "no logical type", so the field was handled as a bare long: a number
            // still worked, but the ISO-8601 form was rejected outright. That made nanos the one
            // timestamp unit where the safe spelling - the one Instant produces - did not work.
            case "timestamp-nanos" -> epoch(node, path, 1_000_000_000L);
            case "date" -> date(node, path);
            case "uuid" -> {
                final var text = text(node, schema, path);
                try {
                    yield UUID.fromString(text).toString();
                } catch (IllegalArgumentException e) {
                    throw new DataException("Field '" + path + "' is declared uuid but '" + text + "' is not one.");
                }
            }
            default -> null;
        };
    }

    private static Object decimal(JsonNode node, Schema schema, LogicalTypes.Decimal type, String path) {
        final BigDecimal decimal;
        if (node.isNumber()) {
            decimal = node.decimalValue();
        } else if (node.isTextual()) {
            try {
                decimal = new BigDecimal(node.textValue());
            } catch (NumberFormatException e) {
                throw new DataException(
                        "Field '" + path + "' is declared decimal but '" + node.textValue() + "' is not a number.");
            }
        } else {
            throw mismatch(path, schema, node, "a number");
        }
        final BigDecimal scaled;
        try {
            // UNNECESSARY, so a value too precise for the schema fails instead of being rounded
            // away silently - losing money quietly is exactly the failure this class exists for.
            scaled = decimal.setScale(type.getScale(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new DataException("Field '" + path + "' has value " + decimal + ", which does not fit scale "
                    + type.getScale() + " without rounding.");
        }
        if (scaled.precision() > type.getPrecision()) {
            throw new DataException("Field '" + path + "' has value " + decimal + ", whose precision "
                    + scaled.precision() + " exceeds the schema's " + type.getPrecision() + ".");
        }
        final var unscaled = scaled.unscaledValue().toByteArray();
        if (schema.getType() == Schema.Type.FIXED) {
            final var padded = new byte[schema.getFixedSize()];
            final var sign = (byte) (scaled.signum() < 0 ? 0xFF : 0x00);
            java.util.Arrays.fill(padded, sign);
            if (unscaled.length > padded.length) {
                throw new DataException("Field '" + path + "' needs " + unscaled.length + " bytes but fixed type "
                        + schema.getFullName() + " holds " + padded.length + ".");
            }
            System.arraycopy(unscaled, 0, padded, padded.length - unscaled.length, unscaled.length);
            return new GenericData.Fixed(schema, padded);
        }
        return ByteBuffer.wrap(unscaled);
    }

    /**
     * Converts a JSON timestamp to the unit the schema declares, given as {@code unitsPerSecond} -
     * 1,000 for {@code timestamp-millis}, 1,000,000 for {@code timestamp-micros}, 1,000,000,000 for
     * {@code timestamp-nanos}. Expressed that way rather than as a multiplier relative to millis so
     * a third unit is a constant rather than another branch; the previous form special-cased millis
     * against "everything else", which is why nanos had nowhere to go.
     *
     * <p>A number is taken to be <em>already</em> in the declared unit and is passed through
     * unscaled; only the ISO-8601 form is converted. That asymmetry is deliberate but easy to
     * trip over, because a bare number carries no unit and nothing here can recover it: epoch
     * millis dropped into a {@code timestamp-micros} field is a valid long that decodes to
     * January 1970, silently. Scaling instead would only move the silence - it would assume
     * millis and corrupt every payload that correctly supplies micros.
     *
     * <p>So the contract is the caller's to keep, and it is stated in
     * {@code docs/events/event-streaming.md}. Ekbatan's own payloads are unaffected: Jackson
     * writes a {@link Instant} as an ISO-8601 string, which takes the converting path.
     *
     * @param node the JSON value.
     * @param path the field path, for error messages.
     * @param unitsPerSecond 1_000 for millis, 1_000_000 for micros, 1_000_000_000 for nanos.
     * @return the epoch value in the declared unit.
     */
    private static long epoch(JsonNode node, String path, long unitsPerSecond) {
        if (node.isNumber()) {
            return node.longValue();
        }
        if (node.isTextual()) {
            try {
                final var instant = Instant.parse(node.textValue());
                final var nanosPerUnit = 1_000_000_000L / unitsPerSecond;
                try {
                    return Math.addExact(
                            Math.multiplyExact(instant.getEpochSecond(), unitsPerSecond),
                            instant.getNano() / nanosPerUnit);
                } catch (ArithmeticException overflow) {
                    // Only reachable for nanos, where a long runs out around the year 2262. Left as
                    // a DataException naming the field rather than a bare ArithmeticException from
                    // inside the converter.
                    throw new DataException("Field '" + path + "' is '" + node.textValue()
                            + "', which does not fit the range its timestamp unit can represent.");
                }
            } catch (DateTimeParseException e) {
                throw new DataException(
                        "Field '" + path + "' is declared a timestamp but '" + node.textValue() + "' is not ISO-8601.");
            }
        }
        throw new DataException("Field '" + path + "' is declared a timestamp but the value is a " + node.getNodeType()
                + "; expected a number or an ISO-8601 string.");
    }

    private static int date(JsonNode node, String path) {
        if (node.isNumber()) {
            return node.intValue();
        }
        if (node.isTextual()) {
            try {
                return (int) LocalDate.parse(node.textValue()).toEpochDay();
            } catch (DateTimeParseException e) {
                throw new DataException(
                        "Field '" + path + "' is declared a date but '" + node.textValue() + "' is not ISO-8601.");
            }
        }
        throw new DataException("Field '" + path + "' is declared a date but the value is a " + node.getNodeType()
                + "; expected a number or an ISO-8601 string.");
    }

    private static String text(JsonNode node, Schema schema, String path) {
        if (!node.isTextual()) {
            throw mismatch(path, schema, node, "a string");
        }
        return node.textValue();
    }

    private static byte[] binary(JsonNode node, Schema schema, String path) {
        if (!node.isTextual()) {
            throw mismatch(path, schema, node, "a base64 string");
        }
        try {
            // Avro's JSON convention for bytes is base64.
            return node.binaryValue();
        } catch (Exception e) {
            throw new DataException(
                    "Field '" + path + "' is declared " + schema.getType() + " so it needs base64 text, but '"
                            + abbreviate(node) + "' could not be decoded.",
                    e);
        }
    }

    private static void requireNumber(JsonNode node, Schema schema, String path) {
        if (!node.isNumber()) {
            throw mismatch(path, schema, node, "a number");
        }
    }

    private static DataException mismatch(String path, Schema schema, JsonNode node, String wanted) {
        final var logical = schema.getLogicalType();
        final var declared = logical != null
                ? schema.getType() + "/" + logical.getName()
                : schema.getType().toString();
        return new DataException("Field '" + path + "' is declared " + declared + " so it needs " + wanted
                + ", but the payload has a " + node.getNodeType() + " (" + abbreviate(node) + ").");
    }

    private static String abbreviate(JsonNode node) {
        final var text = node.toString();
        return text.length() <= 40 ? text : text.substring(0, 40) + "...";
    }
}
