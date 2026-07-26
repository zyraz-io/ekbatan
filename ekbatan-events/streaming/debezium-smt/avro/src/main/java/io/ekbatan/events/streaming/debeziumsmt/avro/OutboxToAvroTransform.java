package io.ekbatan.events.streaming.debeziumsmt.avro;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ekbatan.events.streaming.debeziumsmt.common.ActionEventFields;
import io.ekbatan.events.streaming.debeziumsmt.common.OutboxColumns;
import io.ekbatan.events.streaming.debeziumsmt.common.OutboxRecords;
import io.ekbatan.events.streaming.debeziumsmt.common.PayloadSchemaBindings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.message.BinaryMessageEncoder;
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

    public static final String SCHEMAS_CONFIG = "payload.schemas";
    public static final String ACTION_EVENT_SCHEMA_CONFIG = "action.event.schema";
    public static final String PAYLOAD_FIELD_CONFIG = "payload.field";
    public static final String EVENT_TYPE_FIELD_CONFIG = "event.type.field";
    public static final String NAMESPACE_FIELD_CONFIG = "namespace.field";

    public static final ConfigDef CONFIG_DEF = new ConfigDef()
            .define(
                    SCHEMAS_CONFIG,
                    ConfigDef.Type.STRING,
                    ConfigDef.NO_DEFAULT_VALUE,
                    PayloadSchemaBindings.validator(PayloadSchemaBindings.AVRO),
                    ConfigDef.Importance.HIGH,
                    "Comma-separated mapping of <namespace>.avro.<EventType>:/path/to/schema.avsc")
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
                    "Name of the event type field on the record value")
            .define(
                    NAMESPACE_FIELD_CONFIG,
                    ConfigDef.Type.STRING,
                    "namespace",
                    ConfigDef.Importance.LOW,
                    "Name of the namespace field on the record value; with the event type it selects the schema");

    private static final Logger LOG = LoggerFactory.getLogger(OutboxToAvroTransform.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Payload JSON to Avro. Warnings go through the same warn-once set as everything else. */
    private final JsonToAvro jsonToAvro = new JsonToAvro(this::warnOnceAbout);

    /** Schemas already reported as dropped, so routine housekeeping logs once rather than forever. */
    private final Set<String> warnedDroppedSchemas = ConcurrentHashMap.newKeySet();

    /** Keyed by the schema's fully-qualified name, which is rebuilt per record. */
    private final Map<String, Schema> schemasByQualifiedName = new HashMap<>();

    /** One encoder per schema - constructing one fingerprints the schema. See {@link #writeBinary}. */
    private final Map<Schema, BinaryMessageEncoder<GenericRecord>> encoders = new ConcurrentHashMap<>();

    private Schema actionEventSchema;
    private String payloadField;
    private String eventTypeField;
    private String namespaceField;

    @Override
    public void configure(Map<String, ?> configs) {
        var parsed = CONFIG_DEF.parse(configs);
        this.payloadField = (String) parsed.get(PAYLOAD_FIELD_CONFIG);
        this.eventTypeField = (String) parsed.get(EVENT_TYPE_FIELD_CONFIG);

        this.namespaceField = (String) parsed.get(NAMESPACE_FIELD_CONFIG);

        var bindings = PayloadSchemaBindings.parse(
                (String) parsed.get(SCHEMAS_CONFIG), PayloadSchemaBindings.AVRO, SCHEMAS_CONFIG);
        bindings.forEach((qualifiedName, path) -> {
            var schema = loadSchema(path);
            // An .avsc declares its own full name, so the file can be held to the binding rather
            // than trusted. Without this a mis-wired path is not necessarily an error: unknown
            // fields are dropped with a warning by design, so two events sharing modelId/modelName
            // would encode the overlap and silently discard the rest.
            if (!qualifiedName.equals(schema.getFullName())) {
                throw new IllegalArgumentException(path + " declares '" + schema.getFullName()
                        + "' but is configured as '" + qualifiedName + "'. The schema's namespace and name must"
                        + " match the mapping key exactly.");
            }
            schemasByQualifiedName.put(qualifiedName, schema);
        });

        var actionEventPath = (String) parsed.get(ACTION_EVENT_SCHEMA_CONFIG);
        this.actionEventSchema = loadSchema(actionEventPath);
        ActionEventFields.verifyBindable(
                actionEventSchema.getFields().stream().map(Schema.Field::name).toList(),
                "loaded from " + actionEventPath);
    }

    /**
     * Reads a schema file, closing it afterwards.
     *
     * <p>The stream used to be opened inline as an argument and never closed. Avro will not do it
     * for you - {@code Schema.Parser.parse(InputStream)} calls
     * {@code disable(JsonParser.Feature.AUTO_CLOSE_SOURCE)}, which is Avro stating that closing is
     * the caller's job. Only a handle per configured schema at startup, so nothing was ever going
     * to run out; it was simply a file left open for the life of the connector.
     */
    private static Schema loadSchema(String path) {
        try (var in = Files.newInputStream(Path.of(path))) {
            return new Schema.Parser().parse(in);
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
        var payloadBytes = encodePayload(namespaceOf(row), eventType, payloadJson);
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

    /**
     * Reads the namespace the schema binding is keyed on. Required, not optional: without it the
     * transform would have to guess between two services' identically-named events.
     */
    private String namespaceOf(Struct row) {
        final var column = row.schema().field(namespaceField);
        if (column == null) {
            throw new DataException("Outbox row has no '" + namespaceField + "' column, which is needed to select a"
                    + " payload schema. Set " + NAMESPACE_FIELD_CONFIG + " if the column is named differently.");
        }
        final var namespace = OutboxColumns.text(row, column);
        if (namespace == null || namespace.isBlank()) {
            throw new DataException("Outbox row has a blank '" + namespaceField + "'; it selects the payload schema");
        }
        return namespace;
    }

    private byte[] encodePayload(String namespace, String eventType, String payloadJson) {
        final var qualifiedName = PayloadSchemaBindings.qualifiedName(namespace, PayloadSchemaBindings.AVRO, eventType);
        final var schema = schemasByQualifiedName.get(qualifiedName);
        if (schema == null) {
            throw new DataException("No Avro schema configured for '" + qualifiedName + "'. Add it to "
                    + SCHEMAS_CONFIG + " as " + qualifiedName + ":<path>. Configured: "
                    + new java.util.TreeSet<>(schemasByQualifiedName.keySet()));
        }
        try {
            final var json = objectMapper.readTree(payloadJson);
            final var record = jsonToAvro.convert(json, schema, qualifiedName);
            return writeBinary(schema, record);
        } catch (IOException e) {
            throw new DataException("Failed to encode payload to Avro for " + qualifiedName, e);
        } catch (DataException e) {
            // JsonToAvro's messages already name the offending field path; wrapping would bury the
            // one detail worth reading.
            throw e;
        } catch (RuntimeException e) {
            // IOException covers a malformed document - this SMT is on Jackson 2, where a parse
            // failure is one - but not what Avro raises unchecked. AvroRuntimeException from the
            // encoder escaped bare, giving the operator an Avro error with no indication of which
            // event type produced it, against a documented contract that says failures arrive as
            // DataException.
            throw new DataException("Failed to encode payload to Avro for " + qualifiedName, e);
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

    /**
     * Encodes a record in Avro's <em>single-object encoding</em>: a two-byte marker, the schema's
     * 8-byte fingerprint, then the record's binary form.
     *
     * <p>This used to write the bare binary form, which nothing published could read. Avro's code
     * generator puts {@code fromByteBuffer} on every generated class, so a consumer holding our
     * {@code ekbatan-action-event-avro} jar reaches for it first - and it speaks only this framed
     * form, failing on unframed bytes with {@code BadHeaderException: Not enough header bytes}. The
     * reader we shipped could not read the messages we sent, and no test noticed because every
     * decode site in this repository used {@code binaryDecoder} directly, mirroring the encoder
     * rather than exercising the published contract.
     *
     * <p>Deliberately the one method behind both the envelope and the payload, so a consumer can use
     * the same call at both levels - {@code ActionEvent.fromByteBuffer(value)} and then
     * {@code MyEvent.fromByteBuffer(event.getPayload())}. Framing only the envelope would have left
     * the identical trap one level down.
     *
     * <p>The fingerprint is the reason to cache an encoder per schema: constructing one hashes the
     * schema's canonical form, which is far too costly to repeat per record.
     */
    private byte[] writeBinary(Schema schema, GenericRecord record) throws IOException {
        var buffer = encoders.computeIfAbsent(schema, s -> new BinaryMessageEncoder<>(GenericData.get(), s))
                .encode(record);
        // encode() hands back a buffer whose backing array may be larger than the message, so copy
        // out what is actually between position and limit rather than calling array().
        var bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    @Override
    public ConfigDef config() {
        return CONFIG_DEF;
    }

    @Override
    public void close() {
        schemasByQualifiedName.clear();
    }
}
