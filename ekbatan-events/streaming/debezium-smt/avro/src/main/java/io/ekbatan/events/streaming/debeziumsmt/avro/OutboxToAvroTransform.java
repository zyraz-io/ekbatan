package io.ekbatan.events.streaming.debeziumsmt.avro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.EncoderFactory;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.transforms.Transformation;

/**
 * Encodes a Debezium outbox record end-to-end into Avro binary: the JSON {@code payload} field is
 * encoded against a per-event-type schema, and the whole row is then encoded against the
 * {@code ActionEvent} schema. The record value becomes raw {@code byte[]} — the connector
 * should use {@code ByteArrayConverter}.
 *
 * <p>Records without an {@code event_type} (sentinel rows) pass through unchanged.
 *
 * <p>Config:
 * <ul>
 *   <li>{@code payloadSchemas} — comma-separated {@code eventType:/path/to/schema.avsc} pairs</li>
 *   <li>{@code actionEventSchema} — path to the ActionEvent Avro schema (mandatory)</li>
 *   <li>{@code payload.field} — name of the JSON payload field (default: {@code payload})</li>
 *   <li>{@code event.type.field} — name of the event type field (default: {@code event_type})</li>
 * </ul>
 */
public class OutboxToAvroTransform<R extends ConnectRecord<R>> implements Transformation<R> {

    public static final String SCHEMAS_CONFIG = "payloadSchemas";
    public static final String ACTION_EVENT_SCHEMA_CONFIG = "actionEventSchema";
    public static final String PAYLOAD_FIELD_CONFIG = "payload.field";
    public static final String EVENT_TYPE_FIELD_CONFIG = "event.type.field";

    public static final ConfigDef CONFIG_DEF = new ConfigDef()
            .define(
                    SCHEMAS_CONFIG,
                    ConfigDef.Type.STRING,
                    ConfigDef.Importance.HIGH,
                    "Comma-separated mapping of eventType:/path/to/schema.avsc")
            .define(
                    ACTION_EVENT_SCHEMA_CONFIG,
                    ConfigDef.Type.STRING,
                    ConfigDef.Importance.HIGH,
                    "Path to the ActionEvent Avro schema")
            .define(
                    PAYLOAD_FIELD_CONFIG,
                    ConfigDef.Type.STRING,
                    "payload",
                    ConfigDef.Importance.LOW,
                    "Name of the JSON payload field on the record value")
            .define(
                    EVENT_TYPE_FIELD_CONFIG,
                    ConfigDef.Type.STRING,
                    "event_type",
                    ConfigDef.Importance.LOW,
                    "Name of the event type field on the record value");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Schema> schemasByEventType = new HashMap<>();
    private Schema actionEventSchema;
    private String payloadField;
    private String eventTypeField;

    @Override
    public void configure(Map<String, ?> configs) {
        var parsed = CONFIG_DEF.parse(configs);
        this.payloadField = (String) parsed.get(PAYLOAD_FIELD_CONFIG);
        this.eventTypeField = (String) parsed.get(EVENT_TYPE_FIELD_CONFIG);

        var schemasSpec = (String) parsed.get(SCHEMAS_CONFIG);
        for (var entry : schemasSpec.split(",")) {
            var trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            var idx = trimmed.indexOf(':');
            if (idx < 0) {
                throw new IllegalArgumentException("Invalid schema mapping (expected eventType:path): " + trimmed);
            }
            var eventType = trimmed.substring(0, idx).trim();
            var path = trimmed.substring(idx + 1).trim();
            schemasByEventType.put(eventType, loadSchema(path));
        }

        var actionEventPath = (String) parsed.get(ACTION_EVENT_SCHEMA_CONFIG);
        this.actionEventSchema = loadSchema(actionEventPath);
    }

    private static Schema loadSchema(String path) {
        try {
            return new Schema.Parser().parse(Files.newInputStream(Path.of(path)));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load Avro schema at " + path, e);
        }
    }

    /**
     * Returns {@code true} when the SMT should emit a record for a Debezium change event
     * with the given {@code op} value.
     *
     * <p>The {@code eventlog.events} outbox is append-only from the application's
     * perspective. The only {@code op} values that represent real business events are
     * <ul>
     *   <li>{@code "c"} (create) — a new event row inserted by the action persister, atomic
     *       with the action's data writes.</li>
     *   <li>{@code "r"} (read) — an existing row delivered during Debezium's initial
     *       snapshot. From the consumer's perspective this is a past business event being
     *       (re)played.</li>
     * </ul>
     *
     * <p>Other ops are filtered out so they never become Kafka messages:
     * <ul>
     *   <li>{@code "u"} (update) — fires when the in-process consumer path
     *       ({@code local-event-handler}) flips {@code delivered = TRUE} after fanout. This
     *       is internal bookkeeping, not a new fact.</li>
     *   <li>{@code "d"} (delete) — events are append-only; a deletion is either a
     *       housekeeping action or noise, never a business fact.</li>
     * </ul>
     *
     * <p>A {@code null} {@code op} (rare — record without a Debezium envelope, or an
     * envelope schema lacking the {@code op} field) is passed through: we don't drop
     * records we don't have enough information to classify.
     */
    private static boolean shouldEmitForOp(String op) {
        return op == null || op.equals("c") || op.equals("r");
    }

    @Override
    public R apply(R record) {
        if (record.value() == null) {
            return record;
        }
        if (!(record.value() instanceof Struct struct)) {
            throw new DataException("Expected schemaful Struct record, got: "
                    + record.value().getClass().getName());
        }
        return applyStruct(record, struct);
    }

    private R applyStruct(R record, Struct envelope) {
        // Debezium wraps the row in an envelope: { before, after, source, op, ts_ms, ... }.
        // For inserts/snapshots the row of interest is in `after`. If there's no envelope wrapper,
        // the SMT operates on the value directly.
        final var afterField = envelope.schema().field("after");
        Struct row;
        if (afterField != null) {
            // Drop non-INSERT operations. The `eventlog.events` table is append-only from
            // the application's perspective, but the in-process consumer path (the
            // `local-event-handler` module) flips `delivered = TRUE` after fanout, which
            // generates UPDATE events that don't represent new business facts. We emit
            // only creates ('c') and snapshot reads ('r') as outbox messages.
            final var opField = envelope.schema().field("op");
            final var op = opField != null ? envelope.getString("op") : null;
            if (!shouldEmitForOp(op)) {
                return null; // drop the record from the stream
            }
            row = envelope.getStruct("after");
            if (row == null) {
                return record; // delete event or tombstone
            }
        } else {
            row = envelope;
        }

        var rowSchema = row.schema();
        var eventTypeFieldOnRow = rowSchema.field(eventTypeField);
        var payloadFieldOnRow = rowSchema.field(payloadField);
        if (eventTypeFieldOnRow == null || payloadFieldOnRow == null) {
            return record;
        }
        var eventType = (String) row.get(eventTypeFieldOnRow);
        if (eventType == null) {
            return record;
        }
        var payloadJson = (String) row.get(payloadFieldOnRow);
        if (payloadJson == null) {
            return record;
        }
        var payloadBytes = encodePayload(eventType, payloadJson);
        var actionEventBytes = encodeActionEvent(row, payloadBytes);

        return record.newRecord(
                record.topic(),
                record.kafkaPartition(),
                record.keySchema(),
                record.key(),
                org.apache.kafka.connect.data.Schema.BYTES_SCHEMA,
                actionEventBytes,
                record.timestamp());
    }

    private byte[] encodePayload(String eventType, String payloadJson) {
        final var schema = schemasByEventType.get(eventType);
        if (schema == null) {
            throw new DataException("No Avro schema configured for event type: " + eventType);
        }
        try {
            final var json = objectMapper.readTree(payloadJson);
            final var record = jsonToGenericRecord(json, schema);
            return writeBinary(schema, record);
        } catch (IOException e) {
            throw new DataException("Failed to encode payload to Avro for " + eventType, e);
        }
    }

    private byte[] encodeActionEvent(Struct row, byte[] payloadBytes) {
        var record = new GenericData.Record(actionEventSchema);
        for (var field : actionEventSchema.getFields()) {
            if (field.name().equals(payloadField)) {
                record.put(field.name(), java.nio.ByteBuffer.wrap(payloadBytes));
                continue;
            }
            var rowField = row.schema().field(field.name());
            if (rowField == null) {
                continue;
            }
            record.put(field.name(), row.get(rowField));
        }
        try {
            return writeBinary(actionEventSchema, record);
        } catch (IOException e) {
            throw new DataException("Failed to encode ActionEvent to Avro", e);
        }
    }

    private static byte[] writeBinary(Schema schema, GenericRecord record) throws IOException {
        var out = new ByteArrayOutputStream();
        var encoder = EncoderFactory.get().binaryEncoder(out, null);
        new GenericDatumWriter<GenericRecord>(schema).write(record, encoder);
        encoder.flush();
        return out.toByteArray();
    }

    private GenericRecord jsonToGenericRecord(JsonNode json, Schema schema) {
        var record = new GenericData.Record(schema);
        for (var field : schema.getFields()) {
            var node = json.get(field.name());
            if (node == null || node.isNull()) {
                continue;
            }
            record.put(field.name(), convertValue(node, field.schema()));
        }
        return record;
    }

    private Object convertValue(JsonNode node, Schema schema) {
        try {
            return switch (schema.getType()) {
                case NULL -> null;
                case STRING -> node.asText();
                case INT -> node.asInt();
                case LONG -> node.asLong();
                case FLOAT -> (float) node.asDouble();
                case DOUBLE -> node.asDouble();
                case BOOLEAN -> node.asBoolean();
                case BYTES -> node.binaryValue();
                case FIXED -> new GenericData.Fixed(schema, node.binaryValue());
                case ENUM -> new GenericData.EnumSymbol(schema, node.asText());
                case RECORD -> jsonToGenericRecord(node, schema);
                case ARRAY -> {
                    var list = new ArrayList<>(node.size());
                    for (var element : node) {
                        list.add(convertValue(element, schema.getElementType()));
                    }
                    yield list;
                }
                case MAP -> {
                    var map = new LinkedHashMap<String, Object>();
                    for (var entry : node.properties()) {
                        map.put(entry.getKey(), convertValue(entry.getValue(), schema.getValueType()));
                    }
                    yield map;
                }
                case UNION -> convertUnion(node, schema);
            };
        } catch (IOException e) {
            throw new DataException("Failed to convert value for schema " + schema, e);
        }
    }

    private Object convertUnion(JsonNode node, Schema unionSchema) {
        for (var branch : unionSchema.getTypes()) {
            if (branch.getType() == Schema.Type.NULL) {
                continue;
            }
            try {
                return convertValue(node, branch);
            } catch (Exception ignored) {
                // try next branch
            }
        }
        throw new DataException("No matching union branch for value: " + node);
    }

    @Override
    public ConfigDef config() {
        return CONFIG_DEF;
    }

    @Override
    public void close() {
        schemasByEventType.clear();
    }
}
