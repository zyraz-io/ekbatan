package io.ekbatan.events.streaming.debeziumsmt.protobuf;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import io.ekbatan.events.streaming.debeziumsmt.common.ActionEventFields;
import io.ekbatan.events.streaming.debeziumsmt.common.OutboxColumns;
import io.ekbatan.events.streaming.debeziumsmt.common.OutboxRecords;
import io.ekbatan.events.streaming.debeziumsmt.common.PayloadSchemaBindings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.transforms.Transformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encodes a Debezium outbox record end-to-end into protobuf binary: the JSON {@code payload} field
 * is parsed into a protobuf message matching the event type's descriptor, and the whole row is
 * encoded against the {@code ActionEvent} descriptor. The record value becomes raw {@code byte[]} -
 * the connector should use {@code ByteArrayConverter}.
 *
 * <p>Records without an {@code event_type} (sentinel rows) are dropped.
 *
 * <p>Config:
 * <ul>
 *   <li>{@code payloadDescriptors} - comma-separated {@code eventType:/path/to/desc.desc} pairs.
 *       Each descriptor file must contain a message with the same name as {@code eventType}.</li>
 *   <li>{@code actionEventDescriptor} - path to the ActionEvent protobuf descriptor set.</li>
 *   <li>{@code payload.field} - name of the JSON payload field (default: {@code payload})</li>
 *   <li>{@code event.type.field} - name of the event type field (default: {@code event_type})</li>
 * </ul>
 */
public class OutboxToProtobufTransform<R extends ConnectRecord<R>> implements Transformation<R> {

    public static final String PAYLOAD_DESCRIPTORS_CONFIG = "payload.descriptors";
    public static final String ACTION_EVENT_DESCRIPTOR_CONFIG = "action.event.descriptor";
    public static final String PAYLOAD_FIELD_CONFIG = "payload.field";
    public static final String EVENT_TYPE_FIELD_CONFIG = "event.type.field";
    public static final String NAMESPACE_FIELD_CONFIG = "namespace.field";

    public static final ConfigDef CONFIG_DEF = new ConfigDef()
            .define(
                    PAYLOAD_DESCRIPTORS_CONFIG,
                    ConfigDef.Type.STRING,
                    ConfigDef.NO_DEFAULT_VALUE,
                    PayloadSchemaBindings.validator(PayloadSchemaBindings.PROTOBUF),
                    ConfigDef.Importance.HIGH,
                    "Comma-separated mapping of <namespace>.proto.<EventType>:/path/to/desc.desc")
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
                    "Name of the event type field on the record value")
            .define(
                    NAMESPACE_FIELD_CONFIG,
                    ConfigDef.Type.STRING,
                    "namespace",
                    ConfigDef.Importance.LOW,
                    "Name of the namespace field on the record value; with the event type it selects the descriptor");

    private static final Logger LOG = LoggerFactory.getLogger(OutboxToProtobufTransform.class);

    /** The well-known type every {@code EPOCH_MICROS} field must be declared as. */
    private static final String TIMESTAMP_TYPE = "google.protobuf.Timestamp";

    /**
     * The envelope's fully-qualified name. Fixed, because {@code ActionEvent.proto} is published by
     * this project rather than supplied by the user.
     */
    private static final String ACTION_EVENT_MESSAGE = "io.ekbatan.events.streaming.actionevent.protobuf.ActionEvent";

    /** Keyed by the message's fully-qualified name, which is rebuilt per record. */
    private final Map<String, Descriptor> payloadDescriptorsByQualifiedName = new HashMap<>();

    /** Schemas already reported as dropped, so routine housekeeping logs once rather than forever. */
    private final Set<String> warnedDroppedSchemas = ConcurrentHashMap.newKeySet();

    private Descriptor actionEventDescriptor;
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
                (String) parsed.get(PAYLOAD_DESCRIPTORS_CONFIG),
                PayloadSchemaBindings.PROTOBUF,
                PAYLOAD_DESCRIPTORS_CONFIG);
        bindings.forEach((qualifiedName, path) ->
                payloadDescriptorsByQualifiedName.put(qualifiedName, loadMessageDescriptor(path, qualifiedName)));

        var actionEventPath = (String) parsed.get(ACTION_EVENT_DESCRIPTOR_CONFIG);
        this.actionEventDescriptor = loadMessageDescriptor(actionEventPath, ACTION_EVENT_MESSAGE);
        ActionEventFields.verifyBindable(
                actionEventDescriptor.getFields().stream()
                        .map(FieldDescriptor::getName)
                        .toList(),
                "loaded from " + actionEventPath);
        verifyTimestampFields(actionEventDescriptor, "loaded from " + actionEventPath);
    }

    /**
     * Finds a message by its <em>fully-qualified</em> name.
     *
     * <p>This matched on the simple name and returned the first hit, which is unsafe for a reason
     * that is easy to miss: a descriptor set built with {@code --include_imports} contains the
     * imported files too, so an unrelated library message sharing the short name could be picked
     * instead - and if the two shapes were compatible it would encode without complaint. A
     * fully-qualified name matches at most one message, so the ambiguity cannot arise rather than
     * merely being detected.
     *
     * <p>Only top-level messages are considered; the outbox binds one message per event type.
     */
    private static Descriptor loadMessageDescriptor(String descriptorPath, String qualifiedName) {
        try (var in = Files.newInputStream(Path.of(descriptorPath))) {
            var set = FileDescriptorSet.parseFrom(in);
            var known = new java.util.TreeSet<String>();
            for (var file : buildFileDescriptors(set)) {
                for (var message : file.getMessageTypes()) {
                    if (qualifiedName.equals(message.getFullName())) {
                        return message;
                    }
                    known.add(message.getFullName());
                }
            }
            throw new IllegalArgumentException("Message '" + qualifiedName + "' not found in descriptor set "
                    + descriptorPath + ". It declares: " + known
                    + ". The proto package must be <namespace>.proto for the event's namespace.");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load descriptor from " + descriptorPath, e);
        }
    }

    /**
     * Resolves a descriptor set into built {@link FileDescriptor}s, imports first.
     *
     * <p>Package-private so the tests build fixtures through the same resolver they ship.
     *
     * <p>This walks imports recursively instead of trusting the order files appear in the set, and
     * refuses to build a file whose import is absent. The previous version did neither: it took the
     * set in order and silently dropped any dependency it had not already built. That was invisible
     * while {@code ActionEvent.proto} imported nothing, and became a startup failure the moment it
     * imported {@code google/protobuf/timestamp.proto} - reported by protobuf as a bare "type not
     * found", naming the type rather than the missing import.
     */
    static List<FileDescriptor> buildFileDescriptors(FileDescriptorSet set)
            throws com.google.protobuf.Descriptors.DescriptorValidationException {
        var protosByName = new LinkedHashMap<String, FileDescriptorProto>();
        for (FileDescriptorProto proto : set.getFileList()) {
            protosByName.put(proto.getName(), proto);
        }
        var built = new LinkedHashMap<String, FileDescriptor>();
        for (var name : protosByName.keySet()) {
            resolveFile(name, protosByName, built, new LinkedHashSet<>());
        }
        return List.copyOf(built.values());
    }

    private static FileDescriptor resolveFile(
            String name,
            Map<String, FileDescriptorProto> protosByName,
            Map<String, FileDescriptor> built,
            Set<String> resolving)
            throws com.google.protobuf.Descriptors.DescriptorValidationException {
        var already = built.get(name);
        if (already != null) {
            return already;
        }
        var proto = protosByName.get(name);
        if (proto == null) {
            throw new IllegalArgumentException("Descriptor set does not contain imported file '" + name
                    + "'. Regenerate it with --include_imports so the imports travel with it.");
        }
        if (!resolving.add(name)) {
            throw new IllegalArgumentException("Circular import involving proto file '" + name + "'");
        }
        var deps = new ArrayList<FileDescriptor>();
        for (var depName : proto.getDependencyList()) {
            deps.add(resolveFile(depName, protosByName, built, resolving));
        }
        resolving.remove(name);
        var file = FileDescriptor.buildFrom(proto, deps.toArray(new FileDescriptor[0]));
        built.put(name, file);
        return file;
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
        if (eventType == null) return null;
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
     * Reads the namespace the descriptor binding is keyed on. Required, not optional: without it
     * the transform would have to guess between two services' identically-named events.
     */
    private String namespaceOf(Struct row) {
        final var column = row.schema().field(namespaceField);
        if (column == null) {
            throw new DataException("Outbox row has no '" + namespaceField + "' column, which is needed to select a"
                    + " payload descriptor. Set " + NAMESPACE_FIELD_CONFIG + " if the column is named differently.");
        }
        final var namespace = OutboxColumns.text(row, column);
        if (namespace == null || namespace.isBlank()) {
            throw new DataException(
                    "Outbox row has a blank '" + namespaceField + "'; it selects the payload descriptor");
        }
        return namespace;
    }

    private byte[] encodePayload(String namespace, String eventType, String payloadJson) {
        var qualifiedName = PayloadSchemaBindings.qualifiedName(namespace, PayloadSchemaBindings.PROTOBUF, eventType);
        var descriptor = payloadDescriptorsByQualifiedName.get(qualifiedName);
        if (descriptor == null) {
            throw new DataException("No protobuf descriptor configured for '" + qualifiedName + "'. Add it to "
                    + PAYLOAD_DESCRIPTORS_CONFIG + " as " + qualifiedName + ":<path>. Configured: "
                    + new java.util.TreeSet<>(payloadDescriptorsByQualifiedName.keySet()));
        }
        try {
            var builder = DynamicMessage.newBuilder(descriptor);
            JsonFormat.parser().ignoringUnknownFields().merge(payloadJson, builder);
            return builder.build().toByteArray();
        } catch (IOException e) {
            throw new DataException("Failed to encode payload to protobuf for " + qualifiedName, e);
        } catch (DataException e) {
            throw e;
        } catch (RuntimeException e) {
            // The same shape encodeActionEvent already uses, which this method did not. IOException
            // covers a malformed document, but not everything protobuf raises unchecked -
            // build() throws UninitializedMessageException when a proto2 descriptor leaves a
            // required field unset, for one. Those escaped bare, so the operator saw a protobuf
            // error with no indication of which event type or which row produced it, against a
            // documented contract that says failures arrive as DataException.
            throw new DataException("Failed to encode payload to protobuf for " + qualifiedName, e);
        }
    }

    private byte[] encodeActionEvent(Struct row, byte[] payloadBytes) {
        var builder = DynamicMessage.newBuilder(actionEventDescriptor);
        for (var field : actionEventDescriptor.getFields()) {
            // configure() has already proven every field here has a binding.
            var kind = ActionEventFields.kindOf(field.getName());
            if (kind == ActionEventFields.Kind.PAYLOAD) {
                builder.setField(field, ByteString.copyFrom(payloadBytes));
                continue;
            }
            var column = row.schema().field(sourceColumn(field.getName()));
            if (column == null) {
                // The row does not carry this column at all; leave the field unset rather than
                // inventing a value.
                continue;
            }
            try {
                switch (kind) {
                    case TEXT -> {
                        var text = OutboxColumns.text(row, column);
                        if (text != null) {
                            builder.setField(field, text);
                        }
                    }
                    case EPOCH_MICROS ->
                        builder.setField(field, timestampOf(field, OutboxColumns.epochMicros(row, column)));
                    case BOOL -> builder.setField(field, OutboxColumns.bool(row, column));
                    default -> throw new IllegalStateException("Unhandled binding kind: " + kind);
                }
            } catch (DataException e) {
                throw e;
            } catch (RuntimeException e) {
                // protobuf's setField raises IllegalArgumentException on a type mismatch. The
                // documented contract for this SMT is DataException, and the operator needs the
                // field name to act on it.
                throw new DataException(
                        "Failed to encode ActionEvent field '" + field.getName() + "' from outbox column '"
                                + sourceColumn(field.getName()) + "'",
                        e);
            }
        }
        return builder.build().toByteArray();
    }

    /**
     * Wraps an epoch-microsecond column in the {@code google.protobuf.Timestamp} its field expects.
     *
     * <p>These fields used to be a bare {@code int64} of microseconds, which a consumer had no way
     * to distinguish from milliseconds: reading one as the other silently yields the year 58535 or
     * three weeks past the epoch. {@code Timestamp} carries seconds and nanos separately, so the
     * unit travels with the value and the mistake cannot be expressed.
     *
     * <p>Split with {@link Math#floorDiv} and {@link Math#floorMod} rather than {@code /} and
     * {@code %} so instants before 1970 still produce the non-negative {@code nanos} protobuf
     * requires.
     */
    private static DynamicMessage timestampOf(FieldDescriptor field, long epochMicros) {
        var timestamp = field.getMessageType();
        return DynamicMessage.newBuilder(timestamp)
                .setField(timestamp.findFieldByName("seconds"), Math.floorDiv(epochMicros, 1_000_000L))
                .setField(timestamp.findFieldByName("nanos"), (int) Math.floorMod(epochMicros, 1_000_000L) * 1_000)
                .build();
    }

    /**
     * Fails at startup unless every timestamp field really is a {@code google.protobuf.Timestamp}.
     *
     * <p>Checked here rather than per record so that a descriptor built from an {@code ActionEvent
     * .proto} which reverted these fields to {@code int64} stops the connector, instead of encoding
     * the first row and throwing on a {@code setField} type mismatch.
     */
    private static void verifyTimestampFields(Descriptor descriptor, String schemaDescription) {
        for (var field : descriptor.getFields()) {
            if (ActionEventFields.kindOf(field.getName()) != ActionEventFields.Kind.EPOCH_MICROS) {
                continue;
            }
            var isTimestamp = field.getJavaType() == FieldDescriptor.JavaType.MESSAGE
                    && TIMESTAMP_TYPE.equals(field.getMessageType().getFullName());
            if (!isTimestamp) {
                throw new IllegalArgumentException("ActionEvent schema " + schemaDescription + " declares field '"
                        + field.getName() + "' as " + describeType(field) + "; expected " + TIMESTAMP_TYPE
                        + ". A bare integer cannot say whether it counts millis or micros, which is why"
                        + " this field is a message.");
            }
        }
    }

    private static String describeType(FieldDescriptor field) {
        return field.getJavaType() == FieldDescriptor.JavaType.MESSAGE
                ? field.getMessageType().getFullName()
                : field.getType().name().toLowerCase(java.util.Locale.ROOT);
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

    @Override
    public ConfigDef config() {
        return CONFIG_DEF;
    }

    @Override
    public void close() {
        payloadDescriptorsByQualifiedName.clear();
    }
}
