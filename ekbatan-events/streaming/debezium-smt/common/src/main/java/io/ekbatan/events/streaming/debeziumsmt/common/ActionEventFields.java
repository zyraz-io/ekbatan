package io.ekbatan.events.streaming.debeziumsmt.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.TreeSet;

/**
 * The binding between the {@code ActionEvent} wire schema and the {@code eventlog.events} outbox
 * columns.
 *
 * <p>Both SMTs previously reflected over the target schema, looked up a column of the same name
 * and copied the value across untouched. That is a generic mechanism for a problem that is not
 * generic: the outbox table and the {@code ActionEvent} schema are both owned by this framework
 * and both have a known, fixed shape. Reflection bought nothing and cost the type information,
 * so a column whose Connect type did not happen to match the schema was written blind.
 *
 * <p>Writing the mapping down instead makes the conversion explicit per field, and lets
 * {@link #verifyBindable} reject a schema carrying a field this table does not know at connector
 * startup - rather than silently shipping it unset on every message for the life of the
 * deployment.
 */
public final class ActionEventFields {

    /** How a column is converted before it is written to its {@code ActionEvent} field. */
    public enum Kind {
        /** {@link OutboxColumns#text}. Null leaves the target field unset. */
        TEXT,
        /** {@link OutboxColumns#epochMicros}. */
        EPOCH_MICROS,
        /** {@link OutboxColumns#bool}. */
        BOOL,
        /** The already-encoded payload bytes, not a column read. */
        PAYLOAD
    }

    /** Name of the {@code ActionEvent} field carrying the encoded payload. */
    public static final String PAYLOAD_FIELD = "payload";

    /** Name of the {@code ActionEvent} field carrying the event type discriminator. */
    public static final String EVENT_TYPE_FIELD = "event_type";

    /**
     * Name of the flag the in-process fanout sets once it has handled a row.
     *
     * <p>Load-bearing beyond its own column: when an unwrapped record carries no {@code op}, this
     * is the only way to tell an insert from the fanout's {@code UPDATE}. See
     * {@code OutboxRecords.classify}.
     */
    public static final String DELIVERED_FIELD = "delivered";

    private static final Map<String, Kind> FIELDS = Map.ofEntries(
            Map.entry("id", Kind.TEXT),
            Map.entry("namespace", Kind.TEXT),
            Map.entry("action_id", Kind.TEXT),
            Map.entry("action_name", Kind.TEXT),
            Map.entry("action_params", Kind.TEXT),
            Map.entry("started_date", Kind.EPOCH_MICROS),
            Map.entry("completion_date", Kind.EPOCH_MICROS),
            Map.entry("model_id", Kind.TEXT),
            Map.entry("model_type", Kind.TEXT),
            Map.entry(EVENT_TYPE_FIELD, Kind.TEXT),
            Map.entry(PAYLOAD_FIELD, Kind.PAYLOAD),
            Map.entry("event_date", Kind.EPOCH_MICROS),
            Map.entry("delivered", Kind.BOOL));

    private ActionEventFields() {}

    /** The conversion for an {@code ActionEvent} field, or {@code null} if the table has none. */
    public static Kind kindOf(String actionEventFieldName) {
        return FIELDS.get(actionEventFieldName);
    }

    /**
     * Fails unless every field of the configured {@code ActionEvent} schema has a binding.
     *
     * <p>Called once at {@code configure()} so that adding a field to {@code ActionEvent.avsc} or
     * {@code ActionEvent.proto} without extending this table stops the connector at startup. A
     * schema carrying only a subset of the fields is accepted: the absent ones simply are not
     * written.
     *
     * @param schemaFieldNames every field declared by the loaded ActionEvent schema
     * @param schemaDescription how to identify that schema in the error message
     */
    public static void verifyBindable(Collection<String> schemaFieldNames, String schemaDescription) {
        final var unbound = new ArrayList<String>();
        for (final var name : schemaFieldNames) {
            if (!FIELDS.containsKey(name)) {
                unbound.add(name);
            }
        }
        if (!unbound.isEmpty()) {
            throw new IllegalArgumentException("ActionEvent schema " + schemaDescription + " declares field(s) "
                    + new TreeSet<>(unbound) + " that have no outbox column binding. Known fields: "
                    + new TreeSet<>(FIELDS.keySet()) + ". Extend ActionEventFields if the wire schema gained a field.");
        }
    }
}
