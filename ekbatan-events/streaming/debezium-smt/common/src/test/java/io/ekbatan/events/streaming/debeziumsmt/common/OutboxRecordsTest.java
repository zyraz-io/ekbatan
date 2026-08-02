package io.ekbatan.events.streaming.debeziumsmt.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.events.streaming.debeziumsmt.common.OutboxRecords.Disposition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Deciding what to do with each record a Debezium connector produces.
 *
 * <p>The rule these tests pin down is that nothing is ever handed back untouched. Both SMTs used
 * to {@code return record} for anything they did not recognise, and because the connector runs
 * {@code ByteArrayConverter}, a single heartbeat was enough to kill the task.
 */
class OutboxRecordsTest {

    private static final String EVENT_TYPE = "event_type";
    private static final String PAYLOAD = "payload";

    @Nested
    class OutboxRows {

        @ParameterizedTest
        @ValueSource(strings = {"c", "r"})
        void an_insert_or_snapshot_read_is_encoded(String op) {
            var classified = OutboxRecords.classify(envelope(op, outboxRow()), EVENT_TYPE, PAYLOAD);

            assertThat(classified.disposition).isEqualTo(Disposition.ENCODE);
            assertThat(classified.row).isNotNull();
            assertThat(classified.row.schema().field(EVENT_TYPE)).isNotNull();
        }

        /**
         * When {@code op} is available it is the only signal used - {@code delivered} is
         * deliberately ignored. The flag means "the in-process handler took it", which says nothing
         * about whether Kafka ever received it, so a snapshot has to replay already-delivered rows
         * or a newly bootstrapped pipeline gets no history at all.
         *
         * <p>This exists to stop the apparent inconsistency with the unwrapped path being "tidied
         * up" by applying the delivered check everywhere. Doing that would silently break backfill.
         */
        @ParameterizedTest
        @ValueSource(strings = {"c", "r"})
        void a_business_op_is_encoded_even_when_the_row_is_already_delivered(String op) {
            var rowSchema = SchemaBuilder.struct()
                    .name("eventlog.events.Value")
                    .field(EVENT_TYPE, Schema.OPTIONAL_STRING_SCHEMA)
                    .field(PAYLOAD, Schema.OPTIONAL_STRING_SCHEMA)
                    .field("delivered", Schema.BOOLEAN_SCHEMA)
                    .build();
            var alreadyDelivered = new Struct(rowSchema).put("delivered", true);

            assertThat(OutboxRecords.classify(envelope(op, alreadyDelivered), EVENT_TYPE, PAYLOAD).disposition)
                    .as("op %s must be published regardless of the delivered flag", op)
                    .isEqualTo(Disposition.ENCODE);
        }

        /** ExtractNewRecordState unwraps the envelope; the value is then the row itself. */
        @Test
        void an_already_unwrapped_row_is_encoded() {
            var classified = OutboxRecords.classify(outboxRow(), EVENT_TYPE, PAYLOAD);

            assertThat(classified.disposition).isEqualTo(Disposition.ENCODE);
            assertThat(classified.row).isNotNull();
        }

        /**
         * Unwrapping strips `op`, so the op filter cannot run on the envelope. Configuring
         * ExtractNewRecordState with `add.fields=op` puts it back on the row, and it is honoured -
         * otherwise the UPDATE that flips `delivered` would be republished as a duplicate event.
         */
        @ParameterizedTest
        @ValueSource(strings = {"__op", "op"})
        void an_unwrapped_row_honours_a_carried_op(String field) {
            var schema = SchemaBuilder.struct()
                    .name("eventlog.events.Value")
                    .field(EVENT_TYPE, Schema.OPTIONAL_STRING_SCHEMA)
                    .field(PAYLOAD, Schema.OPTIONAL_STRING_SCHEMA)
                    .field(field, Schema.OPTIONAL_STRING_SCHEMA)
                    .build();

            assertThat(OutboxRecords.classify(new Struct(schema).put(field, "u"), EVENT_TYPE, PAYLOAD).disposition)
                    .isEqualTo(Disposition.SKIP);
            assertThat(OutboxRecords.classify(new Struct(schema).put(field, "c"), EVENT_TYPE, PAYLOAD).disposition)
                    .isEqualTo(Disposition.ENCODE);
        }

        /**
         * With no op on the row there is no way to tell an insert from the delivered flip, so the
         * row is encoded. Documented as a limitation rather than guessed at.
         */
        @Test
        void an_unwrapped_row_with_neither_op_nor_delivered_is_encoded_but_warns() {
            // outboxRow() has no `delivered` column, so neither signal is available.
            var classified = OutboxRecords.classify(outboxRow(), EVENT_TYPE, PAYLOAD);

            assertThat(classified.disposition).isEqualTo(Disposition.ENCODE);
            // Encoded, but never silently: without op we cannot filter the delivered flip, so a
            // deployment also running local-event-handler publishes every event twice.
            assertThat(classified.reason)
                    .as("the operator has to be told this configuration can duplicate events")
                    .contains("local-event-handler")
                    .contains("add.fields=op");
        }

        @Test
        void a_wrapped_row_is_encoded_with_no_warning() {
            var classified = OutboxRecords.classify(envelope("c", outboxRow()), EVENT_TYPE, PAYLOAD);

            assertThat(classified.disposition).isEqualTo(Disposition.ENCODE);
            assertThat(classified.reason).isNull();
        }

        /**
         * With no {@code op}, the row's own {@code delivered} flag carries the same information:
         * the persister inserts every row false, and the fanout is the only writer that sets it
         * true. Pinned in core by {@code EventEntityDeliveredDefaultTest}.
         */
        @Test
        void an_unwrapped_row_already_delivered_is_the_fanout_flip_and_is_skipped() {
            assertThat(unwrappedWithDelivered(Schema.BOOLEAN_SCHEMA, true).disposition)
                    .isEqualTo(Disposition.SKIP);
        }

        @Test
        void an_unwrapped_row_not_yet_delivered_is_the_insert_and_is_encoded() {
            var classified = unwrappedWithDelivered(Schema.BOOLEAN_SCHEMA, false);

            assertThat(classified.disposition).isEqualTo(Disposition.ENCODE);
            // Still worth one warning: a snapshot will not replay rows already marked delivered.
            assertThat(classified.reason).contains("snapshot");
        }

        /** MySQL and MariaDB send the flag as INT16, so the same conversion has to apply here. */
        @Test
        void the_delivered_flag_is_read_through_the_dialect_conversion() {
            assertThat(unwrappedWithDelivered(Schema.INT16_SCHEMA, (short) 1).disposition)
                    .isEqualTo(Disposition.SKIP);
            assertThat(unwrappedWithDelivered(Schema.INT16_SCHEMA, (short) 0).disposition)
                    .isEqualTo(Disposition.ENCODE);
        }

        /** An explicit op beats the inferred signal. */
        @Test
        void a_carried_op_takes_precedence_over_the_delivered_flag() {
            var schema = SchemaBuilder.struct()
                    .name("eventlog.events.Value")
                    .field(EVENT_TYPE, Schema.OPTIONAL_STRING_SCHEMA)
                    .field(PAYLOAD, Schema.OPTIONAL_STRING_SCHEMA)
                    .field("delivered", Schema.BOOLEAN_SCHEMA)
                    .field("__op", Schema.OPTIONAL_STRING_SCHEMA)
                    .build();
            // A snapshot read of a row that was already delivered: op says replay it.
            var row = new Struct(schema).put("delivered", true).put("__op", "r");

            assertThat(OutboxRecords.classify(row, EVENT_TYPE, PAYLOAD).disposition)
                    .isEqualTo(Disposition.ENCODE);
        }

        private static OutboxRecords.Classification unwrappedWithDelivered(Schema schema, Object value) {
            var rowSchema = SchemaBuilder.struct()
                    .name("eventlog.events.Value")
                    .field(EVENT_TYPE, Schema.OPTIONAL_STRING_SCHEMA)
                    .field(PAYLOAD, Schema.OPTIONAL_STRING_SCHEMA)
                    .field("delivered", schema)
                    .build();
            return OutboxRecords.classify(new Struct(rowSchema).put("delivered", value), EVENT_TYPE, PAYLOAD);
        }

        @Test
        void the_configured_column_names_are_what_is_looked_for() {
            var row = new Struct(SchemaBuilder.struct()
                    .name("eventlog.events.Value")
                    .field("kind", Schema.OPTIONAL_STRING_SCHEMA)
                    .field("body", Schema.OPTIONAL_STRING_SCHEMA)
                    .build());

            assertThat(OutboxRecords.classify(row, "kind", "body").disposition).isEqualTo(Disposition.ENCODE);
            assertThat(OutboxRecords.classify(row, EVENT_TYPE, PAYLOAD).disposition)
                    .isEqualTo(Disposition.DROP);
        }
    }

    @Nested
    class RecognisedButNotPublished {

        // Silent on purpose: the local-event-handler flips `delivered` on every row it fans out,
        // so an UPDATE per event is normal traffic. Warning about these would drown the log.

        @ParameterizedTest
        @ValueSource(strings = {"u", "d"})
        void a_non_business_op_is_skipped(String op) {
            var classified = OutboxRecords.classify(envelope(op, outboxRow()), EVENT_TYPE, PAYLOAD);

            assertThat(classified.disposition).isEqualTo(Disposition.SKIP);
        }

        @Test
        void a_change_event_with_no_after_row_is_skipped() {
            var classified = OutboxRecords.classify(envelope("c", null), EVENT_TYPE, PAYLOAD);

            assertThat(classified.disposition).isEqualTo(Disposition.SKIP);
        }
    }

    @Nested
    class DebeziumHousekeeping {

        // Heartbeats, schema-change notices and transaction metadata all lack the `after` field
        // that defines a data change event. Keying off that structure rather than a list of
        // Debezium class names means this keeps working when Debezium renames a schema.

        @Test
        void a_heartbeat_is_dropped_not_passed_through() {
            var heartbeat = new Struct(SchemaBuilder.struct()
                    .name("io.debezium.connector.common.Heartbeat")
                    .field("ts_ms", Schema.OPTIONAL_INT64_SCHEMA)
                    .build());

            var classified = OutboxRecords.classify(heartbeat, EVENT_TYPE, PAYLOAD);

            assertThat(classified.disposition).isEqualTo(Disposition.DROP);
            assertThat(classified.reason).contains("Heartbeat");
        }

        @Test
        void a_schema_change_record_is_dropped_not_passed_through() {
            var schemaChange = new Struct(SchemaBuilder.struct()
                    .name("io.debezium.connector.mysql.SchemaChangeValue")
                    .field("ddl", Schema.OPTIONAL_STRING_SCHEMA)
                    .field("databaseName", Schema.OPTIONAL_STRING_SCHEMA)
                    .build());

            var classified = OutboxRecords.classify(schemaChange, EVENT_TYPE, PAYLOAD);

            assertThat(classified.disposition).isEqualTo(Disposition.DROP);
        }

        @Test
        void a_transaction_metadata_record_is_dropped_not_passed_through() {
            var transaction = new Struct(SchemaBuilder.struct()
                    .name("io.debezium.connector.common.TransactionMetadataValue")
                    .field("status", Schema.OPTIONAL_STRING_SCHEMA)
                    .field("id", Schema.OPTIONAL_STRING_SCHEMA)
                    .build());

            var classified = OutboxRecords.classify(transaction, EVENT_TYPE, PAYLOAD);

            assertThat(classified.disposition).isEqualTo(Disposition.DROP);
        }
    }

    @Nested
    class ForeignDataRows {

        /**
         * A real row from another table. Dropping it would lose someone's data with no trace, so
         * this is the one unrecognised case that stops the connector instead.
         */
        @Test
        void a_row_from_another_table_fails_rather_than_being_discarded() {
            var otherTable = new Struct(SchemaBuilder.struct()
                            .name("inventory.customers.Value")
                            .field("id", Schema.INT32_SCHEMA)
                            .field("email", Schema.STRING_SCHEMA)
                            .build())
                    .put("id", 7)
                    .put("email", "ada@example.com");

            var classified = OutboxRecords.classify(envelope("c", otherTable), EVENT_TYPE, PAYLOAD);

            assertThat(classified.disposition).isEqualTo(Disposition.FAIL);
            assertThat(classified.reason).contains("inventory.customers.Value").contains("table.include.list");
        }
    }

    @Nested
    class Tombstones {

        /**
         * Debezium emits two records for one delete: the change event, and a tombstone under the
         * same key. The change event is skipped as housekeeping, so the tombstone has to be too -
         * they are the same fact. Publishing it would be worse than inconsistent: the key is the
         * outbox row's id, which is the key the ActionEvent went out under, so on a compacted
         * topic it would erase an event that had already been delivered correctly.
         */
        @Test
        void a_tombstone_is_skipped_so_pruning_the_outbox_cannot_unpublish_events() {
            var classified = OutboxRecords.classify(null, EVENT_TYPE, PAYLOAD);

            assertThat(classified.disposition).isEqualTo(Disposition.SKIP);
            assertThat(classified.reason).contains("tombstone");
        }

        @Test
        void a_value_that_is_not_a_struct_fails() {
            var classified = OutboxRecords.classify("just a string", EVENT_TYPE, PAYLOAD);

            assertThat(classified.disposition).isEqualTo(Disposition.FAIL);
            assertThat(classified.reason).contains("Struct");
        }
    }

    private static Struct outboxRow() {
        return new Struct(SchemaBuilder.struct()
                .name("eventlog.events.Value")
                .field(EVENT_TYPE, Schema.OPTIONAL_STRING_SCHEMA)
                .field(PAYLOAD, Schema.OPTIONAL_STRING_SCHEMA)
                .build());
    }

    private static Struct envelope(String op, Struct after) {
        var builder = SchemaBuilder.struct().name("dbserver1.eventlog.events.Envelope");
        builder.field("after", after != null ? after.schema() : outboxRow().schema());
        builder.field("op", Schema.OPTIONAL_STRING_SCHEMA);
        var envelope = new Struct(builder.build()).put("op", op);
        if (after != null) {
            envelope.put("after", after);
        }
        return envelope;
    }
}
