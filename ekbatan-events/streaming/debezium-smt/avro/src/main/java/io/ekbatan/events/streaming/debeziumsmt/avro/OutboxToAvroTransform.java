package io.ekbatan.events.streaming.debeziumsmt.avro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ekbatan.events.streaming.debeziumsmt.common.ActionEventFields;
import io.ekbatan.events.streaming.debeziumsmt.common.OutboxColumns;
import io.ekbatan.events.streaming.debeziumsmt.common.OutboxRecords;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encodes a Debezium outbox record end-to-end into Avro binary: the JSON {@code payload} field is
 * encoded against a per-event-type schema, and the whole row is then encoded against the
 * {@code ActionEvent} schema. The record value becomes raw {@code byte[]} - the connector
 * should use {@code ByteArrayConverter}.
 *
 * <p>Records without an {@code event_type} (sentinel rows) are dropped.
 *
 * <p>Config:
 * <ul>
 *   <li>{@code payloadSchemas} - comma-separated {@code eventType:/path/to/schema.avsc} pairs</li>
 *   <li>{@code actionEventSchema} - path to the ActionEvent Avro schema (mandatory)</li>
 *   <li>{@code payload.field} - name of the JSON payload field (default: {@code payload})</li>
 *   <li>{@code event.type.field} - name of the event type field (default: {@code event_type})</li>
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

    private static final Logger LOG = LoggerFactory.getLogger(OutboxToAvroTransform.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Schemas already reported as dropped, so routine housekeeping logs once rather than forever. */
    private final Set<String> warnedDroppedSchemas = ConcurrentHashMap.newKeySet();

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
        ActionEventFields.verifyBindable(
                actionEventSchema.getFields().stream().map(Schema.Field::name).toList(),
                "loaded from " + actionEventPath);
    }

    private static Schema loadSchema(String path) {
        try {
            return new Schema.Parser().parse(Files.newInputStream(Path.of(path)));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load Avro schema at " + path, e);
        }
    }

    @Override
    public R apply(R record) {
        // Every record gets one of the four outcomes; none of them is "hand it back untouched",
        // because this SMT emits byte[] and its connector runs ByteArrayConverter. See
        // OutboxRecords.
        final var classified = OutboxRecords.classify(record.value(), eventTypeField, payloadField);
        switch (classified.disposition) {
            case SKIP:
                return null;
            case DROP:
                warnOnceAbout(classified.reason);
                return null;
            case FAIL:
                throw new DataException(classified.reason);
            default:
                if (classified.reason != null) {
                    warnOnceAbout(classified.reason);
                }
                return encodeRow(record, classified.row);
        }
    }

    private R encodeRow(R record, Struct row) {

        var rowSchema = row.schema();
        var eventTypeFieldOnRow = rowSchema.field(eventTypeField);
        var payloadFieldOnRow = rowSchema.field(payloadField);
        var eventType = (String) row.get(eventTypeFieldOnRow);
        if (eventType == null) {
            return null;
        }
        var payloadJson = (String) row.get(payloadFieldOnRow);
        if (payloadJson == null) {
            throw new DataException("Outbox payload is null for event type: " + eventType);
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
            // configure() has already proven every field here has a binding.
            var kind = ActionEventFields.kindOf(field.name());
            if (kind == ActionEventFields.Kind.PAYLOAD) {
                record.put(field.name(), java.nio.ByteBuffer.wrap(payloadBytes));
                continue;
            }
            var column = row.schema().field(sourceColumn(field.name()));
            if (column == null) {
                // The row does not carry this column at all; leave the field unset rather than
                // inventing a value.
                continue;
            }
            try {
                switch (kind) {
                    case TEXT -> record.put(field.name(), OutboxColumns.text(row, column));
                    case EPOCH_MICROS -> record.put(field.name(), OutboxColumns.epochMicros(row, column));
                    case BOOL -> record.put(field.name(), OutboxColumns.bool(row, column));
                    default -> throw new IllegalStateException("Unhandled binding kind: " + kind);
                }
            } catch (DataException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new DataException(
                        "Failed to encode ActionEvent field '" + field.name() + "' from outbox column '"
                                + sourceColumn(field.name()) + "'",
                        e);
            }
        }
        try {
            return writeBinary(actionEventSchema, record);
        } catch (IOException e) {
            throw new DataException("Failed to encode ActionEvent to Avro", e);
        }
    }

    /**
     * The outbox column an {@code ActionEvent} field reads from - the same name, except for the
     * one field whose source column is configurable.
     *
     * <p>Note that {@code payload.field} and {@code event.type.field} name the source <em>column
     * on the row</em>, never the target field in the {@code ActionEvent} schema. Both were
     * previously used for both purposes, so overriding either silently emitted every message with
     * that field unset.
     */
    private void warnOnceAbout(String reason) {
        if (warnedDroppedSchemas.add(reason)) {
            LOG.warn(
                    "Dropping records the outbox SMT does not recognise: {}. Further records with this"
                            + " schema are dropped silently.",
                    reason);
        }
    }

    private String sourceColumn(String actionEventFieldName) {
        return ActionEventFields.EVENT_TYPE_FIELD.equals(actionEventFieldName) ? eventTypeField : actionEventFieldName;
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
