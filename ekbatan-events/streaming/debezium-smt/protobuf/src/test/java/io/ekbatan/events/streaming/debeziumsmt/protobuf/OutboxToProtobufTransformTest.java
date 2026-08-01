package io.ekbatan.events.streaming.debeziumsmt.protobuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Round-trips the SMT against the <em>real</em> published {@code ActionEvent} descriptor and then
 * decodes the bytes it emitted.
 *
 * <p>The previous version of this test built a two-field stand-in for {@code ActionEvent} and
 * asserted only that the output was a {@code byte[]}. That is why nobody noticed the encoder was
 * copying raw Connect values into protobuf fields: a test that never parses its own output cannot
 * tell a correct message from a corrupt one, and a fabricated schema never carries the columns
 * whose types actually differ between databases.
 */
class OutboxToProtobufTransformTest {

    private static final Instant WHEN = Instant.parse("2026-07-31T10:15:30.123Z");
    private static final long WHEN_MICROS = WHEN.getEpochSecond() * 1_000_000L + WHEN.getNano() / 1_000L;
    private static final UUID EVENT_ID = UUID.fromString("0198f4a2-1c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID ACTION_ID = UUID.fromString("0198f4a2-1c3d-7e4f-8a9b-0c1d2e3f4a5c");

    private static Path actionEventDescriptor;
    private static Descriptor actionEvent;

    @TempDir
    private Path tempDir;

    @BeforeAll
    static void loadRealDescriptor() throws Exception {
        var path = System.getProperty("ekbatan.actionEventDescriptor");
        assertThat(path)
                .as("the build must pass -Dekbatan.actionEventDescriptor pointing at the generated descriptor set")
                .isNotBlank();
        actionEventDescriptor = Path.of(path);
        assertThat(actionEventDescriptor).exists();
        actionEvent = parseActionEvent(actionEventDescriptor);
    }

    /**
     * A stock MySQL or MariaDB deployment. {@code BOOLEAN} is an alias for {@code TINYINT(1)}, so
     * Debezium sends {@code delivered} as an INT16 - which the previous encoder handed straight to
     * {@code DynamicMessage.setField}, throwing {@code IllegalArgumentException} on the very first
     * row and every row after it.
     */
    @Test
    void mysql_row_round_trips() throws Exception {
        var transform = configuredTransform();

        var message = decode(transform.apply(record(mysqlRow())));

        assertThat(stringField(message, "id")).isEqualTo(EVENT_ID.toString());
        assertThat(stringField(message, "namespace")).isEqualTo("wallet");
        assertThat(stringField(message, "action_id")).isEqualTo(ACTION_ID.toString());
        assertThat(stringField(message, "action_name")).isEqualTo("DepositMoney");
        assertThat(stringField(message, "action_params")).isEqualTo("{\"amount\":\"5.00\"}");
        assertThat(longField(message, "started_date")).isEqualTo(WHEN_MICROS);
        assertThat(longField(message, "completion_date")).isEqualTo(WHEN_MICROS);
        assertThat(longField(message, "event_date")).isEqualTo(WHEN_MICROS);
        assertThat(stringField(message, "model_id")).isEqualTo("wallet-1");
        assertThat(stringField(message, "model_type")).isEqualTo("Wallet");
        assertThat(stringField(message, "event_type")).isEqualTo("TestEvent");
        assertThat(boolField(message, "delivered")).isFalse();
        assertThat(bytesField(message, "payload")).isNotEmpty();
    }

    @Test
    void postgres_row_round_trips() throws Exception {
        var transform = configuredTransform();

        var message = decode(transform.apply(record(postgresRow())));

        assertThat(stringField(message, "id")).isEqualTo(EVENT_ID.toString());
        assertThat(longField(message, "started_date")).isEqualTo(WHEN_MICROS);
        assertThat(boolField(message, "delivered")).isFalse();
    }

    /**
     * The property that matters most: the same logical row produces the same bytes regardless of
     * which database it came from. Postgres worked by luck before this change - every one of its
     * column types happened to line up with the schema - so comparing against it is the sharpest
     * available check on the others.
     */
    @Test
    void every_dialect_produces_identical_bytes_for_the_same_row() throws Exception {
        var transform = configuredTransform();

        var postgres = (byte[]) transform.apply(record(postgresRow())).value();
        var mysql = (byte[]) transform.apply(record(mysqlRow())).value();
        var mysqlMillis = (byte[]) transform.apply(record(mysqlDatetime3Row())).value();
        var binaryUuid = (byte[]) transform.apply(record(binaryUuidRow())).value();

        assertThat(mysql).isEqualTo(postgres);
        assertThat(mysqlMillis).isEqualTo(postgres);
        assertThat(binaryUuid).isEqualTo(postgres);
    }

    /**
     * {@code payload.field} names the source column on the row, never the target field in the
     * ActionEvent schema. It was previously used for both, so overriding it emitted every single
     * message with an empty payload and no error anywhere.
     */
    @Test
    void overriding_the_payload_column_still_populates_the_payload_field() throws Exception {
        var transform = configuredTransform(Map.of(OutboxToProtobufTransform.PAYLOAD_FIELD_CONFIG, "body"));

        var row = renameColumn(postgresRow(), "payload", "body");
        var message = decode(transform.apply(record(row)));

        assertThat(bytesField(message, "payload")).isNotEmpty();
        assertThat(stringField(message, "event_type")).isEqualTo("TestEvent");
    }

    @Test
    void overriding_the_event_type_column_still_populates_the_event_type_field() throws Exception {
        var transform = configuredTransform(Map.of(OutboxToProtobufTransform.EVENT_TYPE_FIELD_CONFIG, "kind"));

        var row = renameColumn(postgresRow(), "event_type", "kind");
        var message = decode(transform.apply(record(row)));

        assertThat(stringField(message, "event_type")).isEqualTo("TestEvent");
        assertThat(bytesField(message, "payload")).isNotEmpty();
    }

    /**
     * The coverage check. Adding a field to {@code ActionEvent.proto} without extending the
     * binding table would otherwise ship that field unset on every message, silently, forever.
     */
    @Test
    void a_schema_field_with_no_column_binding_fails_at_startup() throws Exception {
        var unbound = writeDescriptorSet(
                tempDir.resolve("Unbound.desc"),
                file(
                        "Unbound.proto",
                        message("ActionEvent", stringField("event_type", 1), stringField("surprise", 2))));

        assertThatThrownBy(() -> newTransform(
                        Map.of(OutboxToProtobufTransform.ACTION_EVENT_DESCRIPTOR_CONFIG, unbound.toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("surprise")
                .hasMessageContaining("no outbox column binding");
    }

    @Test
    void a_column_whose_type_cannot_be_converted_raises_a_data_exception_naming_it() throws Exception {
        var transform = configuredTransform();
        // A timestamp column with no Debezium logical type carries no unit.
        var row = replaceColumn(postgresRow(), "started_date", Schema.INT64_SCHEMA, WHEN_MICROS);

        assertThatThrownBy(() -> transform.apply(record(row)))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("started_date");
    }

    @Test
    void sentinel_rows_are_dropped() throws Exception {
        var transform = configuredTransform();
        var row = replaceColumn(postgresRow(), "event_type", Schema.OPTIONAL_STRING_SCHEMA, null);

        assertThat(transform.apply(record(row))).isNull();
    }

    @Test
    void the_emitted_value_is_raw_bytes() throws Exception {
        var transform = configuredTransform();

        var transformed = transform.apply(record(postgresRow()));

        assertThat(transformed.valueSchema()).isEqualTo(Schema.BYTES_SCHEMA);
        assertThat(transformed.value()).isInstanceOf(byte[].class);
    }

    // --- rows as each database's Debezium connector actually produces them -------------------

    /** PostgreSQL: {@code uuid} to string, {@code TIMESTAMP} to micros, real {@code BOOLEAN}. */
    private static Struct postgresRow() {
        return row(
                Schema.STRING_SCHEMA,
                EVENT_ID.toString(),
                debezium("MicroTimestamp", Schema.Type.INT64),
                WHEN_MICROS,
                Schema.BOOLEAN_SCHEMA,
                false);
    }

    /**
     * MySQL: {@code CHAR(36)}, {@code DATETIME(6)} to micros, {@code BOOLEAN} as INT16. MariaDB
     * produces the same shape - its native {@code UUID} also arrives as a string, measured in
     * {@code MariadbSmtIntegrationTest} - so this row stands for both.
     */
    private static Struct mysqlRow() {
        return row(
                Schema.STRING_SCHEMA,
                EVENT_ID.toString(),
                debezium("MicroTimestamp", Schema.Type.INT64),
                WHEN_MICROS,
                Schema.INT16_SCHEMA,
                (short) 0);
    }

    /** MySQL with a coarser column: {@code DATETIME(3)} arrives as millis. */
    private static Struct mysqlDatetime3Row() {
        return row(
                Schema.STRING_SCHEMA,
                EVENT_ID.toString(),
                debezium("Timestamp", Schema.Type.INT64),
                WHEN.toEpochMilli(),
                Schema.INT16_SCHEMA,
                (short) 0);
    }

    /**
     * A column that genuinely reaches Connect as bytes, such as a raw {@code BINARY(16)} under
     * {@code binary.handling.mode=bytes}. Measured in {@code MariadbSmtIntegrationTest}, MariaDB's
     * own native {@code UUID} arrives as a string and takes the same path as MySQL.
     */
    private static Struct binaryUuidRow() {
        var bytes = ByteBuffer.allocate(16)
                .putLong(EVENT_ID.getMostSignificantBits())
                .putLong(EVENT_ID.getLeastSignificantBits())
                .array();
        return row(
                Schema.BYTES_SCHEMA,
                bytes,
                debezium("MicroTimestamp", Schema.Type.INT64),
                WHEN_MICROS,
                Schema.INT16_SCHEMA,
                (short) 0);
    }

    private static Struct row(
            Schema idSchema,
            Object id,
            Schema timestampSchema,
            Object timestamp,
            Schema deliveredSchema,
            Object delivered) {
        var schema = SchemaBuilder.struct()
                .name("eventlog.events.Value")
                .field("id", idSchema)
                .field("namespace", Schema.STRING_SCHEMA)
                .field("action_id", Schema.STRING_SCHEMA)
                .field("action_name", Schema.STRING_SCHEMA)
                .field("action_params", Schema.STRING_SCHEMA)
                .field("started_date", timestampSchema)
                .field("completion_date", timestampSchema)
                .field("model_id", Schema.OPTIONAL_STRING_SCHEMA)
                .field("model_type", Schema.OPTIONAL_STRING_SCHEMA)
                .field("event_type", Schema.OPTIONAL_STRING_SCHEMA)
                .field("payload", Schema.OPTIONAL_STRING_SCHEMA)
                .field("event_date", timestampSchema)
                .field("delivered", deliveredSchema)
                .build();
        return new Struct(schema)
                .put("id", id)
                .put("namespace", "wallet")
                .put("action_id", ACTION_ID.toString())
                .put("action_name", "DepositMoney")
                .put("action_params", "{\"amount\":\"5.00\"}")
                .put("started_date", timestamp)
                .put("completion_date", timestamp)
                .put("model_id", "wallet-1")
                .put("model_type", "Wallet")
                .put("event_type", "TestEvent")
                .put("payload", "{\"name\":\"Ada\"}")
                .put("event_date", timestamp)
                .put("delivered", delivered);
    }

    private static Struct renameColumn(Struct row, String from, String to) {
        var builder = SchemaBuilder.struct().name(row.schema().name());
        for (var field : row.schema().fields()) {
            builder.field(field.name().equals(from) ? to : field.name(), field.schema());
        }
        var schema = builder.build();
        var copy = new Struct(schema);
        for (var field : row.schema().fields()) {
            copy.put(field.name().equals(from) ? to : field.name(), row.get(field));
        }
        return copy;
    }

    private static Struct replaceColumn(Struct row, String name, Schema schema, Object value) {
        var builder = SchemaBuilder.struct().name(row.schema().name());
        for (var field : row.schema().fields()) {
            builder.field(field.name(), field.name().equals(name) ? schema : field.schema());
        }
        var newSchema = builder.build();
        var copy = new Struct(newSchema);
        for (var field : row.schema().fields()) {
            copy.put(field.name(), field.name().equals(name) ? value : row.get(field));
        }
        return copy;
    }

    private static Schema debezium(String simpleName, Schema.Type type) {
        return new SchemaBuilder(type).name("io.debezium.time." + simpleName).build();
    }

    // --- plumbing ----------------------------------------------------------------------------

    private OutboxToProtobufTransform<SourceRecord> configuredTransform() throws Exception {
        return configuredTransform(Map.of());
    }

    private OutboxToProtobufTransform<SourceRecord> configuredTransform(Map<String, String> extra) throws Exception {
        var config = new java.util.HashMap<String, String>(extra);
        config.putIfAbsent(OutboxToProtobufTransform.ACTION_EVENT_DESCRIPTOR_CONFIG, actionEventDescriptor.toString());
        return newTransform(config);
    }

    private OutboxToProtobufTransform<SourceRecord> newTransform(Map<String, String> config) throws Exception {
        var payloadDescriptor = writeDescriptorSet(
                tempDir.resolve("payload.desc"), file("payload.proto", message("TestEvent", stringField("name", 1))));
        var full = new java.util.HashMap<String, String>(config);
        full.putIfAbsent(OutboxToProtobufTransform.PAYLOAD_DESCRIPTORS_CONFIG, "TestEvent:" + payloadDescriptor);
        var transform = new OutboxToProtobufTransform<SourceRecord>();
        transform.configure(full);
        return transform;
    }

    private static DynamicMessage decode(SourceRecord transformed) throws Exception {
        assertThat(transformed).isNotNull();
        return DynamicMessage.parseFrom(actionEvent, (byte[]) transformed.value());
    }

    private static String stringField(DynamicMessage message, String name) {
        return (String) message.getField(actionEvent.findFieldByName(name));
    }

    private static long longField(DynamicMessage message, String name) {
        return (Long) message.getField(actionEvent.findFieldByName(name));
    }

    private static boolean boolField(DynamicMessage message, String name) {
        return (Boolean) message.getField(actionEvent.findFieldByName(name));
    }

    private static byte[] bytesField(DynamicMessage message, String name) {
        return ((ByteString) message.getField(actionEvent.findFieldByName(name))).toByteArray();
    }

    private static Descriptor parseActionEvent(Path descriptorSet) throws Exception {
        try (var in = Files.newInputStream(descriptorSet)) {
            var set = FileDescriptorSet.parseFrom(in);
            for (var proto : set.getFileList()) {
                var built = FileDescriptor.buildFrom(proto, new FileDescriptor[0]);
                var found = built.findMessageTypeByName("ActionEvent");
                if (found != null) {
                    return found;
                }
            }
        }
        throw new IllegalStateException("ActionEvent not found in " + descriptorSet);
    }

    private static Path writeDescriptorSet(Path path, FileDescriptorProto file) throws Exception {
        try (var out = Files.newOutputStream(path)) {
            FileDescriptorSet.newBuilder().addFile(file).build().writeTo(out);
        }
        return path;
    }

    private static FileDescriptorProto file(String name, DescriptorProto message) {
        return FileDescriptorProto.newBuilder()
                .setName(name)
                .setSyntax("proto3")
                .addMessageType(message)
                .build();
    }

    private static DescriptorProto message(String name, FieldDescriptorProto... fields) {
        var builder = DescriptorProto.newBuilder().setName(name);
        for (var field : fields) {
            builder.addField(field);
        }
        return builder.build();
    }

    private static FieldDescriptorProto stringField(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .build();
    }

    private static SourceRecord record(Struct row) {
        var envelopeSchema = SchemaBuilder.struct()
                .name("debezium.Envelope")
                .field("after", row.schema())
                .field("op", Schema.STRING_SCHEMA)
                .build();
        var envelope = new Struct(envelopeSchema).put("after", row).put("op", "c");
        return new SourceRecord(Map.of(), Map.of(), "topic", envelopeSchema, envelope);
    }
}
