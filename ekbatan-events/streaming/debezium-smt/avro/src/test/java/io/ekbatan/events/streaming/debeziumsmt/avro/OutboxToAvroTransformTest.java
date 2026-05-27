package io.ekbatan.events.streaming.debeziumsmt.avro;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutboxToAvroTransformTest {

    @TempDir
    private Path tempDir;

    @Test
    void sentinel_rows_are_dropped() throws Exception {
        var transform = configuredTransform();

        var transformed = transform.apply(record(row(null, null)));

        assertThat(transformed).isNull();
    }

    @Test
    void event_rows_are_encoded_as_bytes() throws Exception {
        var transform = configuredTransform();

        var transformed = transform.apply(record(row("TestEvent", "{\"name\":\"Ada\"}")));

        assertThat(transformed).isNotNull();
        assertThat(transformed.valueSchema()).isEqualTo(Schema.BYTES_SCHEMA);
        assertThat(transformed.value()).isInstanceOf(byte[].class);
    }

    private OutboxToAvroTransform<SourceRecord> configuredTransform() throws Exception {
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

        var actionEventSchema = tempDir.resolve("ActionEvent.avsc");
        Files.writeString(actionEventSchema, """
                {
                  "type": "record",
                  "name": "ActionEvent",
                  "fields": [
                    {"name": "event_type", "type": ["null", "string"], "default": null},
                    {"name": "payload", "type": ["null", "bytes"], "default": null}
                  ]
                }
                """);

        var transform = new OutboxToAvroTransform<SourceRecord>();
        transform.configure(Map.of(
                OutboxToAvroTransform.SCHEMAS_CONFIG,
                "TestEvent:" + payloadSchema,
                OutboxToAvroTransform.ACTION_EVENT_SCHEMA_CONFIG,
                actionEventSchema.toString()));
        return transform;
    }

    private static Struct row(String eventType, String payload) {
        return new Struct(rowSchema()).put("event_type", eventType).put("payload", payload);
    }

    private static SourceRecord record(Struct row) {
        var envelopeSchema = SchemaBuilder.struct()
                .name("debezium.Envelope")
                .field("after", rowSchema())
                .field("op", Schema.STRING_SCHEMA)
                .build();
        var envelope = new Struct(envelopeSchema).put("after", row).put("op", "c");
        return new SourceRecord(Map.of(), Map.of(), "topic", envelopeSchema, envelope);
    }

    private static Schema rowSchema() {
        return SchemaBuilder.struct()
                .name("eventlog.events.Value")
                .field("event_type", Schema.OPTIONAL_STRING_SCHEMA)
                .field("payload", Schema.OPTIONAL_STRING_SCHEMA)
                .build();
    }
}
