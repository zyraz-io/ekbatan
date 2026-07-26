package io.ekbatan.events.streaming.debeziumsmt.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.data.TimeConversions;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.message.BinaryMessageDecoder;
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

    /** `ref` holds text in a long-or-string union - the value the old converter turned into 0. */
    private static final String PAYLOAD_JSON =
            "{\"name\":\"Ada\",\"amount\":77.10,\"at\":\"" + WHEN + "\",\"ref\":\"ORDER-123\"}";

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
     * The contract test that was missing, and whose absence hid a defect for the life of the module.
     *
     * <p>Avro's generator puts {@code fromByteBuffer} on every generated class, so this is the first
     * thing a consumer holding {@code ekbatan-action-event-avro} will call. It reads only Avro's
     * single-object encoding, and this SMT used to emit the bare binary form - so the reader we
     * published could not read a single message we sent, failing with {@code BadHeaderException}.
     *
     * <p>Nothing caught it because all seven decode sites in this repository used
     * {@code binaryDecoder} directly. That mirrors the encoder, so it can only ever confirm that the
     * implementation agrees with itself. This test deliberately goes through the <em>published</em>
     * class instead, which is the only thing a consumer actually experiences.
     */
    @Test
    void the_published_consumer_api_can_read_what_this_smt_emits() throws Exception {
        var transform = configuredTransform();

        var bytes = (byte[]) transform.apply(record(postgresRow())).value();
        // Precisely what the generated class does: its fromByteBuffer delegates to a
        // BinaryMessageDecoder built from this same schema. The generated class cannot be depended
        // on from here - it targets Java 25 while this module targets 21 for the Connect worker - so
        // AvroRetryingEventConsumer calls fromByteBuffer for real and this covers the path cheaply.
        var event = new BinaryMessageDecoder<GenericRecord>(GenericData.get(), actionEventSchema).decode(bytes);

        assertThat(text(event, "id")).isEqualTo(EVENT_ID.toString());
        assertThat(text(event, "event_type")).isEqualTo("TestEvent");
        assertThat(event.get("started_date")).isEqualTo(WHEN_MICROS);
        assertThat(event.get("delivered")).isEqualTo(false);
    }

    /**
     * The same guarantee one level down. {@code payload} is encoded by the very same method, so
     * framing only the envelope would have left an identical trap for the consumer's second call -
     * exactly the mistake of fixing a defect in one layer and leaving it in the next.
     */
    @Test
    void the_payload_is_framed_the_same_way_as_the_envelope() throws Exception {
        var transform = configuredTransform();

        var bytes = (byte[]) transform.apply(record(postgresRow())).value();
        var event = new BinaryMessageDecoder<GenericRecord>(GenericData.get(), actionEventSchema).decode(bytes);
        var payload = (ByteBuffer) event.get("payload");

        assertThat(payload).isNotNull();
        // The two-byte marker Avro's single-object encoding puts in front of every message.
        assertThat(payload.get(payload.position())).isEqualTo((byte) 0xC3);
        assertThat(payload.get(payload.position() + 1)).isEqualTo((byte) 0x01);

        // ...and it really decodes as such, rather than merely starting with the right two bytes.
        var payloadBytes = new byte[payload.remaining()];
        payload.duplicate().get(payloadBytes);
        var decoded = new BinaryMessageDecoder<GenericRecord>(
                        GenericData.get(), new Schema.Parser().parse(Files.readString(lastPayloadSchema)))
                .decode(payloadBytes);
        assertThat(decoded.get("name").toString()).isEqualTo("Ada");
    }

    /**
     * The unit tripwire. These fields were once a bare {@code long}, and a long of microseconds is
     * indistinguishable from a long of milliseconds: a consumer guessing wrong lands in the year
     * 58535 or three weeks after the epoch, silently. Declaring the logical type moves the unit out
     * of our prose and into the schema, where a consumer's code generator applies it for them.
     *
     * <p>Deliberately {@code timestamp-micros} and not {@code timestamp-nanos}: Avro only learned
     * the nanosecond type in 1.12, so consumers on 1.11 would silently fall back to a bare long -
     * reintroducing exactly this ambiguity - and the outbox columns are {@code TIMESTAMP} /
     * {@code DATETIME(6)}, which hold no nanoseconds to carry.
     */
    @Test
    void every_timestamp_field_declares_its_unit() {
        for (var name : List.of("started_date", "completion_date", "event_date")) {
            var field = actionEventSchema.getField(name);

            assertThat(field.schema().getLogicalType())
                    .as(
                            "%s must declare its unit; a bare long cannot say whether it counts millis" + " or micros",
                            name)
                    .isEqualTo(LogicalTypes.timestampMicros());
        }
    }

    /**
     * That the declared unit and the written value agree. The declaration is worth nothing on its
     * own - it would be actively harmful if the encoder wrote millis into a field advertising
     * micros - so this reads the emitted bytes back through a conversion-aware reader, the way a
     * consumer's generated class does, and compares against the instant that went in.
     */
    @Test
    void a_consumer_applying_the_declared_unit_recovers_the_original_instant() throws Exception {
        var transform = configuredTransform();
        var model = new GenericData();
        model.addLogicalTypeConversion(new TimeConversions.TimestampMicrosConversion());

        var bytes = (byte[]) transform.apply(record(postgresRow())).value();
        var record = new BinaryMessageDecoder<GenericRecord>(model, actionEventSchema).decode(bytes);

        assertThat(record.get("started_date")).isEqualTo(WHEN);
        assertThat(record.get("completion_date")).isEqualTo(WHEN);
        assertThat(record.get("event_date")).isEqualTo(WHEN);
    }

    /**
     * Declaring the unit was a free change on the wire, and this pins that so it stays free. A
     * logical type annotates its underlying primitive rather than replacing it, so the bytes are
     * those of a plain long and a consumer still holding the old schema keeps reading them.
     *
     * <p>Without this, a later "improvement" to a type that does change the encoding - a string, or
     * a record of seconds and nanos - would break every deployed consumer with nothing to catch it.
     */
    @Test
    void declaring_the_unit_did_not_change_the_bytes() throws Exception {
        var transform = configuredTransform();
        var beforeTheChange = new Schema.Parser()
                .parse(Files.readString(actionEventSchemaPath)
                        .replace("{\"type\": \"long\", \"logicalType\": \"timestamp-micros\"}", "\"long\""));

        var bytes = (byte[]) transform.apply(record(postgresRow())).value();
        // A decoder built from the OLD schema, reading bytes written with the new one. Avro's
        // parsing canonical form strips logical types, so both schemas fingerprint identically and
        // the framed message resolves against either.
        var record = new BinaryMessageDecoder<GenericRecord>(GenericData.get(), beforeTheChange).decode(bytes);

        assertThat(beforeTheChange.getField("started_date").schema().getLogicalType())
                .as("the stand-in for the old schema must really be the un-annotated one")
                .isNull();
        assertThat(record.get("started_date")).isEqualTo(WHEN_MICROS);
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
     * Step 3: the file must declare the name it is bound to.
     *
     * <p>Avro has no in-file lookup - one {@code .avsc} is one schema - so a mis-wired path cannot
     * be caught the way protobuf catches it. It also does not reliably fail on its own: unknown
     * fields are dropped with a warning by design, so pointing an event at another event's schema
     * encodes whatever the two happen to share and silently discards the rest. Framework events all
     * carry {@code modelId} and {@code modelName}, so that overlap is never empty.
     */
    @Test
    void a_schema_that_declares_a_different_name_is_refused_at_startup() throws Exception {
        var mismatched = tempDir.resolve("Mismatched.avsc");
        Files.writeString(mismatched, """
                {"type":"record","name":"SomethingElse","namespace":"other.avro",
                 "fields":[{"name":"name","type":"string"}]}
                """);

        assertThatThrownBy(() -> newTransform(Map.of(
                        OutboxToAvroTransform.SCHEMAS_CONFIG,
                        PAYLOAD_SCHEMA_KEY + ":" + mismatched,
                        OutboxToAvroTransform.ACTION_EVENT_SCHEMA_CONFIG,
                        actionEventSchemaPath.toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other.avro.SomethingElse")
                .hasMessageContaining(PAYLOAD_SCHEMA_KEY);
    }

    /** The namespace is what distinguishes two services' identically-named events; a row without one cannot be routed. */
    @Test
    void a_row_with_no_namespace_column_is_refused() throws Exception {
        var transform = configuredTransform();
        var row = renameColumn(postgresRow(), "namespace", "tenant");

        assertThatThrownBy(() -> transform.apply(record(row)))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("namespace");
    }

    /** A namespace with no binding must name what it looked for, not just fail. */
    @Test
    void an_unconfigured_namespace_names_the_key_it_wanted() throws Exception {
        var transform = configuredTransform();
        var row = replaceColumn(
                postgresRow(), "namespace", org.apache.kafka.connect.data.Schema.STRING_SCHEMA, "other.tenant");

        assertThatThrownBy(() -> transform.apply(record(row)))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("other.tenant.avro.TestEvent");
    }

    /**
     * A malformed payload must arrive as a {@code DataException} like every other bad row.
     *
     * <p>Jackson 3 moved {@code JacksonException} to extend {@code RuntimeException} rather than
     * {@code IOException}, so a {@code catch (IOException)} around {@code readTree} no longer
     * covers a parse failure. The raw Jackson error escapes instead - carrying a line and column
     * but nothing about which event type or which outbox row produced it.
     */
    @Test
    void a_malformed_payload_raises_a_data_exception_naming_the_event() throws Exception {
        var transform = configuredTransform();
        var row = replaceColumn(
                postgresRow(),
                "payload",
                org.apache.kafka.connect.data.Schema.OPTIONAL_STRING_SCHEMA,
                "this is not json{{{");

        assertThatThrownBy(() -> transform.apply(record(row)))
                .isInstanceOf(DataException.class)
                .hasMessageContaining(PAYLOAD_SCHEMA_KEY);
    }

    /**
     * A bad mapping is reported when the configuration is validated, not when the task starts.
     *
     * <p>{@code ConfigDef.validate} is what Kafka Connect's validation endpoint calls before it
     * creates a connector. With no validator attached the mapping had no rule to fail, so a typo
     * was accepted, the connector was created, the task started and only then did
     * {@code configure()} throw - leaving a connector that exists and does not work, and an
     * operator reading task logs to find a mistake that was visible in the text they submitted.
     */
    @Test
    void a_malformed_mapping_is_reported_by_config_validation() {
        var values = OutboxToAvroTransform.CONFIG_DEF.validate(Map.of(
                OutboxToAvroTransform.SCHEMAS_CONFIG, "OrderCreated:/schemas/order.avsc",
                OutboxToAvroTransform.ACTION_EVENT_SCHEMA_CONFIG, "/schemas/ActionEvent.avsc"));

        var mapping = values.stream()
                .filter(v -> v.name().equals(OutboxToAvroTransform.SCHEMAS_CONFIG))
                .findFirst()
                .orElseThrow();

        assertThat(mapping.errorMessages()).isNotEmpty();
        assertThat(mapping.errorMessages().toString()).contains("<namespace>");
    }

    /** Pasting a protobuf key into this transform is caught at validation too, not at startup. */
    @Test
    void a_protobuf_key_is_reported_by_config_validation() {
        var values = OutboxToAvroTransform.CONFIG_DEF.validate(Map.of(
                OutboxToAvroTransform.SCHEMAS_CONFIG, "wallet.proto.TestEvent:/schemas/x.avsc",
                OutboxToAvroTransform.ACTION_EVENT_SCHEMA_CONFIG, "/schemas/ActionEvent.avsc"));

        var mapping = values.stream()
                .filter(v -> v.name().equals(OutboxToAvroTransform.SCHEMAS_CONFIG))
                .findFirst()
                .orElseThrow();

        assertThat(mapping.errorMessages().toString()).contains("avro");
    }

    /** A well-formed mapping must pass cleanly - the rule is syntax only, it does no file I/O. */
    @Test
    void a_well_formed_mapping_passes_validation_without_touching_the_filesystem() {
        var values = OutboxToAvroTransform.CONFIG_DEF.validate(Map.of(
                OutboxToAvroTransform.SCHEMAS_CONFIG,
                PAYLOAD_SCHEMA_KEY + ":/nowhere/does/not/exist.avsc",
                OutboxToAvroTransform.ACTION_EVENT_SCHEMA_CONFIG,
                "/nowhere/ActionEvent.avsc"));

        assertThat(values.stream().flatMap(v -> v.errorMessages().stream())).isEmpty();
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

    /**
     * Decodes the embedded payload, which no test did before - which is why a decimal crashed and a
     * multi-branch union silently wrote 0 over the real value.
     */
    @Test
    void the_payload_decodes_with_its_real_values() throws Exception {
        var transform = configuredTransform();
        var payloadSchema = new org.apache.avro.Schema.Parser().parse(Files.readString(lastPayloadSchema));

        var envelope = decode(transform.apply(record(postgresRow())));
        var payloadBuffer = (ByteBuffer) envelope.get("payload");
        var payloadBytes = new byte[payloadBuffer.remaining()];
        payloadBuffer.duplicate().get(payloadBytes);
        var payload = new BinaryMessageDecoder<GenericRecord>(GenericData.get(), payloadSchema).decode(payloadBytes);

        assertThat(payload.get("name").toString()).isEqualTo("Ada");
        assertThat(payload.get("at")).isEqualTo(WHEN.toEpochMilli());
        assertThat(new java.math.BigDecimal(new java.math.BigInteger(((ByteBuffer) payload.get("amount")).array()), 2))
                .isEqualByComparingTo(new java.math.BigDecimal("77.10"));
        // The regression: text in a ["null","long","string"] union used to arrive as 0.
        assertThat(payload.get("ref").toString()).isEqualTo("ORDER-123");
    }

    @Test
    void the_emitted_value_is_raw_bytes() throws Exception {
        var transform = configuredTransform();

        var transformed = transform.apply(record(postgresRow()));

        assertThat(transformed.valueSchema()).isEqualTo(org.apache.kafka.connect.data.Schema.BYTES_SCHEMA);
        assertThat(transformed.value()).isInstanceOf(byte[].class);
    }

    /**
     * A heartbeat previously came back untouched, and {@code ByteArrayConverter} cannot serialize
     * a Struct - so one heartbeat killed the connector task. Verified end to end in
     * {@code MysqlSmtIntegrationTest}, which now runs with heartbeats and schema changes enabled.
     */
    @Test
    void debezium_housekeeping_is_dropped_rather_than_passed_through() throws Exception {
        var transform = configuredTransform();
        var heartbeat = new Struct(SchemaBuilder.struct()
                .name("io.debezium.connector.common.Heartbeat")
                .field("ts_ms", org.apache.kafka.connect.data.Schema.OPTIONAL_INT64_SCHEMA)
                .build());

        assertThat(transform.apply(rawRecord(heartbeat))).isNull();
    }

    /** A real row from another table must stop the connector, not vanish. */
    @Test
    void a_row_from_another_table_raises_a_data_exception() throws Exception {
        var transform = configuredTransform();
        var other = new Struct(SchemaBuilder.struct()
                        .name("inventory.customers.Value")
                        .field("id", org.apache.kafka.connect.data.Schema.INT32_SCHEMA)
                        .build())
                .put("id", 7);

        assertThatThrownBy(() -> transform.apply(record(other)))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("inventory.customers.Value")
                .hasMessageContaining("table.include.list");
    }

    /** Finding 26: the tombstone companion to a delete used to be published. */
    @Test
    void a_tombstone_is_not_published() throws Exception {
        var transform = configuredTransform();
        var tombstone = new SourceRecord(Map.of(), Map.of(), "topic", null, "key", null, null);

        assertThat(transform.apply(tombstone)).isNull();
    }

    private static SourceRecord rawRecord(Struct value) {
        return new SourceRecord(Map.of(), Map.of(), "topic", value.schema(), value);
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
                .put("payload", PAYLOAD_JSON)
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

    /**
     * The row fixture's namespace is {@code wallet}, so the binding key - and the schema's own
     * declared full name - must be {@code wallet.avro.TestEvent}.
     */
    private static final String PAYLOAD_SCHEMA_KEY = "wallet.avro.TestEvent";

    private static Path lastPayloadSchema;

    private OutboxToAvroTransform<SourceRecord> newTransform(Map<String, String> config) throws Exception {
        var payloadSchema = tempDir.resolve("TestEvent.avsc");
        lastPayloadSchema = payloadSchema;
        // Deliberately not just a string: a decimal, a timestamp and a multi-branch union are the
        // shapes the old hand-rolled converter got wrong, and the shapes no payload test covered.
        Files.writeString(payloadSchema, """
                {
                  "type": "record",
                  "name": "TestEvent",
                  "namespace": "wallet.avro",
                  "fields": [
                    {"name": "name", "type": "string"},
                    {"name": "amount", "type":
                      {"type": "bytes", "logicalType": "decimal", "precision": 10, "scale": 2}},
                    {"name": "at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "ref", "type": ["null", "long", "string"], "default": null}
                  ]
                }
                """);
        var full = new HashMap<>(config);
        full.putIfAbsent(OutboxToAvroTransform.SCHEMAS_CONFIG, PAYLOAD_SCHEMA_KEY + ":" + payloadSchema);
        var transform = new OutboxToAvroTransform<SourceRecord>();
        transform.configure(full);
        return transform;
    }

    /**
     * Decodes through Avro's single-object framing, which is what this SMT emits and what the
     * generated {@code ActionEvent.fromByteBuffer} expects. This helper used to call
     * {@code binaryDecoder} directly - the encoder's mirror image - so it agreed with the SMT no
     * matter what either of them did, and never noticed the published reader could not.
     */
    private static GenericRecord decode(SourceRecord transformed) throws Exception {
        assertThat(transformed).isNotNull();
        return new BinaryMessageDecoder<GenericRecord>(GenericData.get(), actionEventSchema)
                .decode((byte[]) transformed.value());
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
