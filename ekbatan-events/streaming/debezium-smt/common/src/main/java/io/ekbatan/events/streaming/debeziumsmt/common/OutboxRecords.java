package io.ekbatan.events.streaming.debeziumsmt.common;

import org.apache.kafka.connect.data.Struct;

/**
 * Decides what an SMT should do with one record from a Debezium stream.
 *
 * <p>A Debezium connector emits more than the rows it captures: heartbeats, schema-change
 * notices, and transaction-metadata records all travel through the same transform chain. Both
 * SMTs used to hand anything they did not recognise straight back with {@code return record},
 * which is the ordinary courtesy for a transform - but these SMTs emit {@code byte[]} and their
 * connectors are therefore pinned to {@code ByteArrayConverter}, which can serialize only bytes
 * or null. Returning an untouched {@code Struct} guarantees the converter throws, and one
 * heartbeat is enough to kill the task.
 *
 * <p>So there is no pass-through outcome here, only four real ones:
 *
 * <ul>
 *   <li>{@link Disposition#ENCODE} - an outbox row.
 *   <li>{@link Disposition#SKIP} - something we recognise and deliberately do not publish: the
 *       {@code UPDATE} that flips {@code delivered}, a delete, or the tombstone that accompanies
 *       one. Routine and high-volume, so silent.
 *   <li>{@link Disposition#DROP} - Debezium's own housekeeping, which is not an outbox record at
 *       all. Discarding it is correct, but the caller should say so once per schema so that it is
 *       never invisible.
 *   <li>{@link Disposition#FAIL} - a data row from some other table. That means
 *       {@code table.include.list} is wider than the outbox, and silently discarding somebody's
 *       data would be worse than stopping.
 * </ul>
 *
 * <p>The signal separating DROP from FAIL is structural rather than a list of Debezium class
 * names: every housekeeping record lacks the {@code after} field that defines a data change
 * event. Keying off structure instead of names means this does not quietly stop working when
 * Debezium renames a schema.
 */
public final class OutboxRecords {

    /** What the caller should do with the record. */
    public enum Disposition {
        /** An outbox row: encode it. */
        ENCODE,
        /** Recognised, deliberately not published. Silent - this is normal, frequent traffic. */
        SKIP,
        /** Not recognised as an outbox record. Drop it, but log once per schema. */
        DROP,
        /** A data row from another table. Stop rather than lose it silently. */
        FAIL
    }

    /** The outcome of {@link #classify}, plus whatever the caller needs to act on it. */
    public static final class Classification {

        public final Disposition disposition;

        /** The row to encode. Non-null exactly when {@link #disposition} is {@code ENCODE}. */
        public final Struct row;

        /**
         * Why, for the log line or the exception message. Usually null on {@code ENCODE} - when it
         * is not, the record is still encoded but the caller should report the reason once.
         */
        public final String reason;

        private Classification(Disposition disposition, Struct row, String reason) {
            this.disposition = disposition;
            this.row = row;
            this.reason = reason;
        }

        static Classification encode(Struct row) {
            return new Classification(Disposition.ENCODE, row, null);
        }

        static Classification encodeButWarn(Struct row, String warning) {
            return new Classification(Disposition.ENCODE, row, warning);
        }

        static Classification skip(String reason) {
            return new Classification(Disposition.SKIP, null, reason);
        }

        static Classification drop(String reason) {
            return new Classification(Disposition.DROP, null, reason);
        }

        static Classification fail(String reason) {
            return new Classification(Disposition.FAIL, null, reason);
        }
    }

    private OutboxRecords() {}

    /**
     * Whether a Debezium {@code op} represents a business fact worth publishing.
     *
     * <p>{@code c} is an insert by the action persister and {@code r} is the same row replayed
     * during a snapshot. {@code u} is the local-event-handler flipping {@code delivered}, which is
     * bookkeeping rather than a new fact, and the outbox is append-only so {@code d} is
     * housekeeping or noise. Debezium 3.5 also defines {@code t} (truncate) and {@code m}
     * (a Postgres logical-decoding message); neither is a business fact either.
     *
     * <p>Deliberately an allow-list rather than a deny-list, so an operation added by a future
     * Debezium release is withheld rather than published unexamined. A null {@code op} is left to
     * the caller's structural checks rather than guessed at.
     */
    public static boolean shouldEmitForOp(String op) {
        return op == null || op.equals("c") || op.equals("r");
    }

    /**
     * Classifies one record from the connector.
     *
     * @param value the record value, of any shape - including {@code null} for a tombstone
     * @param eventTypeColumn configured name of the event-type column on the outbox row
     * @param payloadColumn configured name of the payload column on the outbox row
     */
    public static Classification classify(Object value, String eventTypeColumn, String payloadColumn) {
        if (value == null) {
            // Debezium emits two records for a delete: the change event, and a tombstone under the
            // same key. The change event is skipped further down as housekeeping, so publishing
            // the tombstone would contradict that - and because the key is the outbox row's id,
            // which is the key the ActionEvent was published under, a compacted topic would treat
            // it as an instruction to erase an event that was already delivered correctly. Pruning
            // old outbox rows must not unpublish the facts they recorded.
            return Classification.skip("tombstone for a deleted outbox row");
        }
        if (!(value instanceof Struct envelope)) {
            return Classification.fail("expected a schemaful Struct record, got "
                    + value.getClass().getName());
        }
        final var schemaName = envelope.schema().name();
        final var afterField = envelope.schema().field("after");

        if (afterField == null) {
            // No `after` at all. Either the connector unwrapped the envelope for us
            // (ExtractNewRecordState), in which case the value is the row itself and carries the
            // outbox columns, or this is one of Debezium's own records.
            if (hasOutboxColumns(envelope, eventTypeColumn, payloadColumn)) {
                // Unwrapping discards `op` along with the rest of the envelope, so the filter
                // above cannot run here. When ExtractNewRecordState is configured with
                // `add.fields=op` the row still carries it and we honour it; otherwise an insert
                // and the `delivered` flip are genuinely indistinguishable, and encoding is the
                // only honest option - see the note in docs/events/event-streaming.md.
                final var unwrappedOp = unwrappedOp(envelope);
                if (unwrappedOp != null) {
                    return shouldEmitForOp(unwrappedOp)
                            ? Classification.encode(envelope)
                            : Classification.skip("unwrapped change event with op '" + unwrappedOp
                                    + "', which is not a business fact");
                }
                // No op. Fall back to the row's own `delivered` flag, which carries the same
                // information for the one UPDATE this table ever sees: SingleTableJsonEventPersister
                // inserts every row with delivered = FALSE, and the only writer that sets it TRUE
                // is the local-event-handler fanout. So delivered = TRUE means "this is the flip",
                // and delivered = FALSE means "this is the insert".
                //
                // That couples this SMT to a framework invariant three modules away, so the
                // invariant is pinned by tests on both sides - see EventEntityDeliveredDefaultTest
                // in ekbatan-core and the unwrapped scenario in the dialects e2e module. If the
                // persister ever starts inserting delivered = TRUE, those fail loudly rather than
                // letting this silently publish nothing.
                final var deliveredField = envelope.schema().field(ActionEventFields.DELIVERED_FIELD);
                if (deliveredField != null) {
                    if (OutboxColumns.bool(envelope, deliveredField)) {
                        return Classification.skip("unwrapped row with delivered = TRUE, which is the"
                                + " local-event-handler fanout flip rather than a new event");
                    }
                    return Classification.encodeButWarn(
                            envelope,
                            "unwrapped rows carry no 'op' field, so delivered = FALSE is being used as the insert"
                                    + " signal. Rows already marked delivered are therefore not replayed during a"
                                    + " snapshot. Configure ExtractNewRecordState with add.fields=op, or run this"
                                    + " SMT before it, to filter on op instead.");
                }

                // Neither op nor delivered: nothing distinguishes an insert from the fanout flip.
                return Classification.encodeButWarn(
                        envelope,
                        "unwrapped rows carry neither an 'op' field nor a '" + ActionEventFields.DELIVERED_FIELD
                                + "' column, so the UPDATE that sets delivered = TRUE cannot be filtered. If the"
                                + " local-event-handler module is running against this outbox, every event is"
                                + " published twice. Configure ExtractNewRecordState with add.fields=op, or run"
                                + " this SMT before it.");
            }
            return Classification.drop("record with no 'after' field and no outbox columns, schema " + schemaName
                    + " - treated as Debezium housekeeping (heartbeat, schema change or transaction metadata)");
        }

        final var opField = envelope.schema().field("op");
        final var op = opField != null ? envelope.getString("op") : null;
        if (!shouldEmitForOp(op)) {
            return Classification.skip("change event with op '" + op + "', which is not a business fact");
        }

        final var row = envelope.getStruct("after");
        if (row == null) {
            return Classification.skip("change event with op '" + op + "' and a null 'after'");
        }

        if (!hasOutboxColumns(row, eventTypeColumn, payloadColumn)) {
            // A real data row, just not one of ours. Dropping it would lose somebody's data
            // silently; the connector is configured to capture a table it should not be.
            return Classification.fail("row from " + row.schema().name() + " has no '" + eventTypeColumn + "' and '"
                    + payloadColumn + "' columns, so it is not an Ekbatan outbox row. Narrow"
                    + " table.include.list to the eventlog.events table, or correct payload.field /"
                    + " event.type.field");
        }

        return Classification.encode(row);
    }

    /**
     * The {@code op} carried by an already-unwrapped row, or {@code null} if it carries none.
     *
     * <p>{@code ExtractNewRecordState} with {@code add.fields=op} copies it onto the row, prefixed
     * with {@code __} by default; a deployment that sets {@code add.fields.prefix} to empty gets a
     * bare {@code op}. Both are checked. The outbox table has no column of either name, so neither
     * can collide with real data.
     */
    private static String unwrappedOp(Struct row) {
        for (final var candidate : new String[] {"__op", "op"}) {
            final var field = row.schema().field(candidate);
            if (field != null && row.get(field) instanceof String op) {
                return op;
            }
        }
        return null;
    }

    private static boolean hasOutboxColumns(Struct value, String eventTypeColumn, String payloadColumn) {
        final var schema = value.schema();
        return schema.field(eventTypeColumn) != null && schema.field(payloadColumn) != null;
    }
}
