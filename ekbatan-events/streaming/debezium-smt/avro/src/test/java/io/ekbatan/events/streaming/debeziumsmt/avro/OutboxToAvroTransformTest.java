package io.ekbatan.events.streaming.debeziumsmt.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DecoderFactory;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Round-trips the SMT against the <em>real</em> published {@code ActionEvent.avsc} and then
 * decodes the bytes it emitted.
 *
 * <p>The previous version of this test built a two-field stand-in for {@code ActionEvent} and
 * asserted only that the output was a {@code byte[]}. That is why nobody noticed the encoder was
 * copying raw Connect values into Avro fields: a test that never parses its own output cannot
 * tell a correct message from a corrupt one, and a fabricated schema never carries the columns
 * whose types actually differ between databases.
 */
class OutboxToAvroTransformTest {

    private static final Instant WHEN = Instant.parse("2026-07-31T10:15:30.123Z");
    private static final long WHEN_MICROS = WHEN.getEpochSecond() * 1_000_000L + WHEN.getNano() / 1_000L;
    private static final UUID EVENT_ID = UUID.fromString("0198f4a2-1c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID ACTION_ID = UUID.fromString("0198f4a2-1c3d-7e4f-8a9b-0c1d2e3f4a5c");

    private static Path actionEventSchemaPath;
    private static Schema actionEventSchema;

    @TempDir
    private Path tempDir;

    @BeforeAll
    static void loadRealSchema() throws Exception {
        var path = System.getProperty("ekbatan.actionEventSchema");
        assertThat(path)
                .as("the build must pass -Dekbatan.actionEventSchema pointing at the published ActionEvent.avsc")
                .isNotBlank();
        actionEventSchemaPath = Path.of(path);
        assertThat(actionEventSchemaPath).exists();
        actionEventSchema = new Schema.Parser().parse(Files.readString(actionEventSchemaPath));
    }

    /**
     * A stock MySQL or MariaDB deployment. {@code BOOLEAN} is an alias for {@code TINYINT(1)}, so
     * Debezium sends {@code delivered} as an INT16 where the schema declares a boolean.
     */
    @Test
    void mysql_row_round_trips() throws Exception {
        var transform = configuredTransform();

        var record = decode(transform.apply(record(mysqlRow())));

        assertThat(text(record, "id")).isEqualTo(EVENT_ID.toString());
        assertThat(text(record, "namespace")).isEqualTo("wallet");
        assertThat(text(record, "action_id")).isEqualTo(ACTION_ID.toString());
        assertThat(text(record, "action_name")).isEqualTo("DepositMoney");
        assertThat(text(record, "action_params")).isEqualTo("{\"amount\":\"5.00\"}");
        assertThat(record.get("started_date")).isEqualTo(WHEN_MICROS);
        assertThat(record.get("completion_date")).isEqualTo(WHEN_MICROS);
        assertThat(record.get("event_date")).isEqualTo(WHEN_MICROS);
        assertThat(text(record, "model_id")).isEqualTo("wallet-1");
        assertThat(text(record, "model_type")).isEqualTo("Wallet");
        assertThat(text(record, "event_type")).isEqualTo("TestEvent");
        assertThat(record.get("delivered")).isEqualTo(false);
        assertThat((ByteBuffer) record.get("payload")).isNotNull();
    }

    @Test
    void postgres_row_round_trips() throws Exception {
        var transform = configuredTransform();

        var record = decode(transform.apply(record(postgresRow())));

        assertThat(text(record, "id")).isEqualTo(EVENT_ID.toString());
        assertThat(record.get("started_date")).isEqualTo(WHEN_MICROS);
        assertThat(record.get("delivered")).isEqualTo(false);
    }

    /**
     * The property that matters most: the same logical row produces the same bytes regardless of
     * which database it came from.
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
     * message with a null payload and no error anywhere.
     */
    @Test
    void overriding_the_payload_column_still_populates_the_payload_field() throws Exception {
        var transform = configuredTransform(Map.of(OutboxToAvroTransform.PAYLOAD_FIELD_CONFIG, "body"));

        var record = decode(transform.apply(record(renameColumn(postgresRow(), "payload", "body"))));

        assertThat((ByteBuffer) record.get("payload")).isNotNull();
        assertThat(text(record, "event_type")).isEqualTo("TestEvent");
    }

    @Test
    void overriding_the_event_type_column_still_populates_the_event_type_field() throws Exception {
        var transform = configuredTransform(Map.of(OutboxToAvroTransform.EVENT_TYPE_FIELD_CONFIG, "kind"));

        var record = decode(transform.apply(record(renameColumn(postgresRow(), "event_type", "kind"))));

        assertThat(text(record, "event_type")).isEqualTo("TestEvent");
        assertThat((ByteBuffer) record.get("payload")).isNotNull();
    }

    /**
     * The coverage check. Adding a field to {@code ActionEvent.avsc} without extending the binding
     * table would otherwise ship that field unset on every message, silently, forever.
     */
    @Test
    void a_schema_field_with_no_column_binding_fails_at_startup() throws Exception {
        var unbound = tempDir.resolve("Unbound.avsc");
        Files.writeString(unbound, """
                {
                  "type": "record",
                  "name": "ActionEvent",
                  "fields": [
                    {"name": "event_type", "type": ["null", "string"], "default": null},
                    {"name": "surprise", "type": ["null", "string"], "default": null}
                  ]
                }
                """);

        assertThatThrownBy(() ->
                        newTransform(Map.of(OutboxToAvroTransform.ACTION_EVENT_SCHEMA_CONFIG, unbound.toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("surprise")
                .hasMessageContaining("no outbox column binding");
    }

    @Test
    void a_column_whose_type_cannot_be_converted_raises_a_data_exception_naming_it() throws Exception {
        var transform = configuredTransform();
        var row = replaceColumn(
                postgresRow(), "started_date", org.apache.kafka.connect.data.Schema.INT64_SCHEMA, WHEN_MICROS);

        assertThatThrownBy(() -> transform.apply(record(row)))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("started_date");
    }

    @Test
    void sentinel_rows_are_dropped() throws Exception {
        var transform = configuredTransform();
        var row = replaceColumn(
                postgresRow(), "event_type", org.apache.kafka.connect.data.Schema.OPTIONAL_STRING_SCHEMA, null);

        assertThat(transform.apply(record(row))).isNull();
    }

    @Test
    void the_emitted_value_is_raw_bytes() throws Exception {
        var transform = configuredTransform();

        var transformed = transform.apply(record(postgresRow()));

        assertThat(transformed.valueSchema()).isEqualTo(org.apache.kafka.connect.data.Schema.BYTES_SCHEMA);
        assertThat(transformed.value()).isInstanceOf(byte[].class);
    }

    // --- rows as each database's Debezium connector actually produces them -------------------

    private static Struct postgresRow() {
        return row(
                org.apache.kafka.connect.data.Schema.STRING_SCHEMA,
                EVENT_ID.toString(),
                debezium("MicroTimestamp", org.apache.kafka.connect.data.Schema.Type.INT64),
                WHEN_MICROS,
                org.apache.kafka.connect.data.Schema.BOOLEAN_SCHEMA,
                false);
    }

    private static Struct mysqlRow() {
        return row(
                org.apache.kafka.connect.data.Schema.STRING_SCHEMA,
                EVENT_ID.toString(),
                debezium("MicroTimestamp", org.apache.kafka.connect.data.Schema.Type.INT64),
                WHEN_MICROS,
                org.apache.kafka.connect.data.Schema.INT16_SCHEMA,
                (short) 0);
    }

    private static Struct mysqlDatetime3Row() {
        return row(
                org.apache.kafka.connect.data.Schema.STRING_SCHEMA,
                EVENT_ID.toString(),
                debezium("Timestamp", org.apache.kafka.connect.data.Schema.Type.INT64),
                WHEN.toEpochMilli(),
                org.apache.kafka.connect.data.Schema.INT16_SCHEMA,
                (short) 0);
    }

    private static Struct binaryUuidRow() {
        var bytes = ByteBuffer.allocate(16)
                .putLong(EVENT_ID.getMostSignificantBits())
                .putLong(EVENT_ID.getLeastSignificantBits())
                .array();
        return row(
                org.apache.kafka.connect.data.Schema.BYTES_SCHEMA,
                bytes,
                debezium("MicroTimestamp", org.apache.kafka.connect.data.Schema.Type.INT64),
                WHEN_MICROS,
                org.apache.kafka.connect.data.Schema.INT16_SCHEMA,
                (short) 0);
    }

    private static Struct row(
            org.apache.kafka.connect.data.Schema idSchema,
            Object id,
            org.apache.kafka.connect.data.Schema timestampSchema,
            Object timestamp,
            org.apache.kafka.connect.data.Schema deliveredSchema,
            Object delivered) {
        var stringSchema = org.apache.kafka.connect.data.Schema.STRING_SCHEMA;
        var optionalString = org.apache.kafka.connect.data.Schema.OPTIONAL_STRING_SCHEMA;
        var schema = SchemaBuilder.struct()
                .name("eventlog.events.Value")
                .field("id", idSchema)
                .field("namespace", stringSchema)
                .field("action_id", stringSchema)
                .field("action_name", stringSchema)
                .field("action_params", stringSchema)
                .field("started_date", timestampSchema)
                .field("completion_date", timestampSchema)
                .field("model_id", optionalString)
                .field("model_type", optionalString)
                .field("event_type", optionalString)
                .field("payload", optionalString)
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
        var copy = new Struct(builder.build());
        for (var field : row.schema().fields()) {
            copy.put(field.name().equals(from) ? to : field.name(), row.get(field));
        }
        return copy;
    }

    private static Struct replaceColumn(
            Struct row, String name, org.apache.kafka.connect.data.Schema schema, Object value) {
        var builder = SchemaBuilder.struct().name(row.schema().name());
        for (var field : row.schema().fields()) {
            builder.field(field.name(), field.name().equals(name) ? schema : field.schema());
        }
        var copy = new Struct(builder.build());
        for (var field : row.schema().fields()) {
            copy.put(field.name(), field.name().equals(name) ? value : row.get(field));
        }
        return copy;
    }

    private static org.apache.kafka.connect.data.Schema debezium(
            String simpleName, org.apache.kafka.connect.data.Schema.Type type) {
        return new SchemaBuilder(type).name("io.debezium.time." + simpleName).build();
    }

    // --- plumbing ----------------------------------------------------------------------------

    private OutboxToAvroTransform<SourceRecord> configuredTransform() throws Exception {
        return configuredTransform(Map.of());
    }

    private OutboxToAvroTransform<SourceRecord> configuredTransform(Map<String, String> extra) throws Exception {
        var config = new HashMap<>(extra);
        config.putIfAbsent(OutboxToAvroTransform.ACTION_EVENT_SCHEMA_CONFIG, actionEventSchemaPath.toString());
        return newTransform(config);
    }

    private OutboxToAvroTransform<SourceRecord> newTransform(Map<String, String> config) throws Exception {
        var payloadSchema = tempDir.resolve("TestEvent.avsc");
        Files.writeString(payloadSchema, """
                {
                  "type": "record",
                  "name": "TestEvent",
                  "fields": [
                    {"name": "name", "type": "string"}
                  ]
                }
                """);
        var full = new HashMap<>(config);
        full.putIfAbsent(OutboxToAvroTransform.SCHEMAS_CONFIG, "TestEvent:" + payloadSchema);
        var transform = new OutboxToAvroTransform<SourceRecord>();
        transform.configure(full);
        return transform;
    }

    private static GenericRecord decode(SourceRecord transformed) throws Exception {
        assertThat(transformed).isNotNull();
        var reader = new GenericDatumReader<GenericRecord>(actionEventSchema);
        var decoder = DecoderFactory.get().binaryDecoder((byte[]) transformed.value(), null);
        return reader.read(null, decoder);
    }

    private static String text(GenericRecord record, String field) {
        var value = record.get(field);
        return value == null ? null : value.toString();
    }

    private static SourceRecord record(Struct row) {
        var envelopeSchema = SchemaBuilder.struct()
                .name("debezium.Envelope")
                .field("after", row.schema())
                .field("op", org.apache.kafka.connect.data.Schema.STRING_SCHEMA)
                .build();
        var envelope = new Struct(envelopeSchema).put("after", row).put("op", "c");
        return new SourceRecord(Map.of(), Map.of(), "topic", envelopeSchema, envelope);
    }
}
