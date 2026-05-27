package io.ekbatan.events.streaming.debeziumsmt.protobuf;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutboxToProtobufTransformTest {

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

    private OutboxToProtobufTransform<SourceRecord> configuredTransform() throws Exception {
        var payloadDescriptor = writeDescriptorSet(
                tempDir.resolve("payload.desc"), file("payload.proto", message("TestEvent", stringField("name", 1))));
        var actionEventDescriptor = writeDescriptorSet(
                tempDir.resolve("ActionEvent.desc"),
                file(
                        "ActionEvent.proto",
                        message("ActionEvent", stringField("event_type", 1), bytesField("payload", 2))));

        var transform = new OutboxToProtobufTransform<SourceRecord>();
        transform.configure(Map.of(
                OutboxToProtobufTransform.PAYLOAD_DESCRIPTORS_CONFIG,
                "TestEvent:" + payloadDescriptor,
                OutboxToProtobufTransform.ACTION_EVENT_DESCRIPTOR_CONFIG,
                actionEventDescriptor.toString()));
        return transform;
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
        return field(name, number, FieldDescriptorProto.Type.TYPE_STRING);
    }

    private static FieldDescriptorProto bytesField(String name, int number) {
        return field(name, number, FieldDescriptorProto.Type.TYPE_BYTES);
    }

    private static FieldDescriptorProto field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setType(type)
                .build();
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
