package io.ekbatan.events.streaming.debeziumsmt.protobuf;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.transforms.Transformation;

/**
 * Encodes a Debezium outbox record end-to-end into protobuf binary: the JSON {@code payload} field
 * is parsed into a protobuf message matching the event type's descriptor, and the whole row is
 * encoded against the {@code ActionEvent} descriptor. The record value becomes raw {@code byte[]} —
 * the connector should use {@code ByteArrayConverter}.
 *
 * <p>Config:
 * <ul>
 *   <li>{@code payloadDescriptors} — comma-separated {@code eventType:/path/to/desc.desc} pairs.
 *       Each descriptor file must contain a message with the same name as {@code eventType}.</li>
 *   <li>{@code actionEventDescriptor} — path to the ActionEvent protobuf descriptor set.</li>
 *   <li>{@code payload.field} — name of the JSON payload field (default: {@code payload})</li>
 *   <li>{@code event.type.field} — name of the event type field (default: {@code event_type})</li>
 * </ul>
 */
public class OutboxToProtobufTransform<R extends ConnectRecord<R>> implements Transformation<R> {

    public static final String PAYLOAD_DESCRIPTORS_CONFIG = "payloadDescriptors";
    public static final String ACTION_EVENT_DESCRIPTOR_CONFIG = "actionEventDescriptor";
    public static final String PAYLOAD_FIELD_CONFIG = "payload.field";
    public static final String EVENT_TYPE_FIELD_CONFIG = "event.type.field";

    public static final ConfigDef CONFIG_DEF = new ConfigDef()
            .define(
                    PAYLOAD_DESCRIPTORS_CONFIG,
                    ConfigDef.Type.STRING,
                    ConfigDef.Importance.HIGH,
                    "Comma-separated mapping of eventType:/path/to/desc.desc")
            .define(
                    ACTION_EVENT_DESCRIPTOR_CONFIG,
                    ConfigDef.Type.STRING,
                    ConfigDef.Importance.HIGH,
                    "Path to the ActionEvent protobuf descriptor set")
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

    private final Map<String, Descriptor> payloadDescriptorsByEventType = new HashMap<>();
    private Descriptor actionEventDescriptor;
    private String payloadField;
    private String eventTypeField;

    @Override
    public void configure(Map<String, ?> configs) {
        var parsed = CONFIG_DEF.parse(configs);
        this.payloadField = (String) parsed.get(PAYLOAD_FIELD_CONFIG);
        this.eventTypeField = (String) parsed.get(EVENT_TYPE_FIELD_CONFIG);

        var spec = (String) parsed.get(PAYLOAD_DESCRIPTORS_CONFIG);
        for (var entry : spec.split(",")) {
            var trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            var idx = trimmed.indexOf(':');
            if (idx < 0) {
                throw new IllegalArgumentException("Invalid descriptor mapping (expected eventType:path): " + trimmed);
            }
            var eventType = trimmed.substring(0, idx).trim();
            var path = trimmed.substring(idx + 1).trim();
            payloadDescriptorsByEventType.put(eventType, loadMessageDescriptor(path, eventType));
        }

        var actionEventPath = (String) parsed.get(ACTION_EVENT_DESCRIPTOR_CONFIG);
        this.actionEventDescriptor = loadMessageDescriptor(actionEventPath, "ActionEvent");
    }

    private static Descriptor loadMessageDescriptor(String descriptorPath, String messageName) {
        try (var in = Files.newInputStream(Path.of(descriptorPath))) {
            var set = FileDescriptorSet.parseFrom(in);
            var files = buildFileDescriptors(set);
            for (var file : files) {
                var desc = file.findMessageTypeByName(messageName);
                if (desc != null) return desc;
            }
            throw new IllegalArgumentException(
                    "Message '" + messageName + "' not found in descriptor set " + descriptorPath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load descriptor from " + descriptorPath, e);
        }
    }

    private static List<FileDescriptor> buildFileDescriptors(FileDescriptorSet set)
            throws com.google.protobuf.Descriptors.DescriptorValidationException {
        var byName = new HashMap<String, FileDescriptor>();
        var result = new ArrayList<FileDescriptor>();
        for (FileDescriptorProto proto : set.getFileList()) {
            var deps = new ArrayList<FileDescriptor>();
            for (var depName : proto.getDependencyList()) {
                var dep = byName.get(depName);
                if (dep != null) deps.add(dep);
            }
            var file = FileDescriptor.buildFrom(proto, deps.toArray(new FileDescriptor[0]));
            byName.put(proto.getName(), file);
            result.add(file);
        }
        return result;
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
        if (record.value() == null) return record;
        if (!(record.value() instanceof Struct struct)) {
            throw new DataException("Expected schemaful Struct record, got: "
                    + record.value().getClass().getName());
        }
        return applyStruct(record, struct);
    }

    private R applyStruct(R record, Struct envelope) {
        final var afterField = envelope.schema().field("after");
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
        }
        Struct row = afterField != null ? envelope.getStruct("after") : envelope;
        if (row == null) return record; // delete / tombstone

        var rowSchema = row.schema();
        var eventTypeFieldOnRow = rowSchema.field(eventTypeField);
        var payloadFieldOnRow = rowSchema.field(payloadField);
        if (eventTypeFieldOnRow == null || payloadFieldOnRow == null) return record;

        var eventType = (String) row.get(eventTypeFieldOnRow);
        if (eventType == null) return record;
        var payloadJson = (String) row.get(payloadFieldOnRow);
        if (payloadJson == null) return record;

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
        var descriptor = payloadDescriptorsByEventType.get(eventType);
        if (descriptor == null) {
            throw new DataException("No protobuf descriptor configured for event type: " + eventType);
        }
        try {
            var builder = DynamicMessage.newBuilder(descriptor);
            JsonFormat.parser().ignoringUnknownFields().merge(payloadJson, builder);
            return builder.build().toByteArray();
        } catch (IOException e) {
            throw new DataException("Failed to encode payload to protobuf for " + eventType, e);
        }
    }

    private byte[] encodeActionEvent(Struct row, byte[] payloadBytes) {
        var builder = DynamicMessage.newBuilder(actionEventDescriptor);
        for (var field : actionEventDescriptor.getFields()) {
            if (field.getName().equals(payloadField)) {
                builder.setField(field, ByteString.copyFrom(payloadBytes));
                continue;
            }
            var rowField = row.schema().field(field.getName());
            if (rowField == null) continue;
            var value = row.get(rowField);
            if (value == null) continue;
            builder.setField(field, value);
        }
        return builder.build().toByteArray();
    }

    @Override
    public ConfigDef config() {
        return CONFIG_DEF;
    }

    @Override
    public void close() {
        payloadDescriptorsByEventType.clear();
    }
}
