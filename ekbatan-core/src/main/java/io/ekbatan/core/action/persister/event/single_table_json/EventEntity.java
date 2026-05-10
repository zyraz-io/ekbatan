package io.ekbatan.core.action.persister.event.single_table_json;

import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.Validate;
import tools.jackson.databind.node.ObjectNode;

/**
 * The shape of a row in the default {@code eventlog.events} table written by
 * {@link SingleTableJsonEventPersister}: action context (namespace, actionId, actionName,
 * params, started/completion timestamps) plus event payload (modelId, modelType, eventType,
 * JSON payload), plus a {@link #delivered} flag for in-process EventHandler delivery.
 *
 * <p>For actions that emit zero events, one sentinel row is still written with
 * {@code modelId}, {@code modelType}, {@code eventType}, and {@code payload} all {@code null}
 * — downstream CDC consumers can correlate every action to a row in the eventlog regardless
 * of whether it emitted events.
 */
public final class EventEntity {

    /** Stable per-row identifier (primary key of {@code eventlog.events}). */
    public final UUID id;

    /** The producing executor's logical namespace. */
    public final String namespace;

    /** Identifier of the producing action invocation (same value across all rows from one action). */
    public final UUID actionId;

    /** Simple class name of the producing action. */
    public final String actionName;

    /** The action's typed params, serialized into JSON. */
    public final ObjectNode actionParams;

    /** When the action's {@code perform()} began. */
    public final Instant startedDate;

    /** When the action's persist phase committed. */
    public final Instant completionDate;

    /** Primary identifier of the affected model; {@code null} for sentinel rows. */
    public final String modelId;

    /** Simple class name of the affected model; {@code null} for sentinel rows. */
    public final String modelType;

    /** Simple class name of the event subtype; {@code null} for sentinel rows. */
    public final String eventType;

    /** Event payload as JSON; {@code null} for sentinel rows. */
    public final ObjectNode payload;

    /** When the event was logically produced (typically equal to {@link #completionDate}). */
    public final Instant eventDate;

    /** Whether the in-process EventHandler delivery has marked this row delivered. */
    public final boolean delivered;

    private EventEntity(Builder builder) {
        this.id = Validate.notNull(builder.id, "id cannot be null");
        this.namespace = Validate.notNull(builder.namespace, "namespace cannot be null");
        this.actionId = Validate.notNull(builder.actionId, "actionId cannot be null");
        this.actionName = Validate.notNull(builder.actionName, "actionName cannot be null");
        this.actionParams = Validate.notNull(builder.actionParams, "actionParams cannot be null");
        this.startedDate = Validate.notNull(builder.startedDate, "startedDate cannot be null");
        this.completionDate = Validate.notNull(builder.completionDate, "completionDate cannot be null");
        this.modelId = builder.modelId;
        this.modelType = builder.modelType;
        this.eventType = builder.eventType;
        this.payload = builder.payload;
        this.eventDate = Validate.notNull(builder.eventDate, "eventDate cannot be null");
        this.delivered = builder.delivered;
    }

    /**
     * Convenience factory that returns a builder pre-populated with the row's mandatory fields;
     * call {@link Builder#delivered(boolean)} to set the delivery flag and then
     * {@link Builder#build()}.
     *
     * @param id row primary key.
     * @param namespace producing executor's logical namespace.
     * @param actionId producing action invocation id.
     * @param actionName producing action's simple class name.
     * @param actionParams producing action's typed params, serialized to JSON.
     * @param startedDate when the action's {@code perform()} began.
     * @param completionDate when the action's persist phase committed.
     * @param modelId affected model's primary identifier (nullable).
     * @param modelType affected model's simple class name (nullable).
     * @param eventType event subtype's simple class name (nullable).
     * @param payload event payload as JSON (nullable).
     * @param eventDate when the event was logically produced.
     * @return a builder pre-populated with the supplied fields.
     */
    public static Builder createEventEntity(
            UUID id,
            String namespace,
            UUID actionId,
            String actionName,
            ObjectNode actionParams,
            Instant startedDate,
            Instant completionDate,
            String modelId,
            String modelType,
            String eventType,
            ObjectNode payload,
            Instant eventDate) {
        return new Builder()
                .id(id)
                .namespace(namespace)
                .actionId(actionId)
                .actionName(actionName)
                .actionParams(actionParams)
                .startedDate(startedDate)
                .completionDate(completionDate)
                .modelId(modelId)
                .modelType(modelType)
                .eventType(eventType)
                .payload(payload)
                .eventDate(eventDate);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;
        final var eventEntity = (EventEntity) other;
        return id.equals(eventEntity.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Fluent builder for {@link EventEntity}. Typically obtained via {@link #createEventEntity}. */
    public static final class Builder {
        private UUID id;
        private String namespace;
        private UUID actionId;
        private String actionName;
        private ObjectNode actionParams;
        private Instant startedDate;
        private Instant completionDate;
        private String modelId;
        private String modelType;
        private String eventType;
        private ObjectNode payload;
        private Instant eventDate;
        private boolean delivered = false;

        private Builder() {}

        /**
         * Sets the row primary key.
         *
         * @param id the row primary key.
         * @return this builder, for chaining.
         */
        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        /**
         * Sets the namespace.
         *
         * @param namespace producing executor's logical namespace.
         * @return this builder, for chaining.
         */
        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        /**
         * Sets the producing action's invocation id.
         *
         * @param actionId producing action invocation id.
         * @return this builder, for chaining.
         */
        public Builder actionId(UUID actionId) {
            this.actionId = actionId;
            return this;
        }

        /**
         * Sets the producing action's class name.
         *
         * @param actionName producing action's simple class name.
         * @return this builder, for chaining.
         */
        public Builder actionName(String actionName) {
            this.actionName = actionName;
            return this;
        }

        /**
         * Sets the producing action's serialized params.
         *
         * @param actionParams producing action's typed params, serialized to JSON.
         * @return this builder, for chaining.
         */
        public Builder actionParams(ObjectNode actionParams) {
            this.actionParams = actionParams;
            return this;
        }

        /**
         * Sets when the producing action's perform() began.
         *
         * @param startedDate the action start instant.
         * @return this builder, for chaining.
         */
        public Builder startedDate(Instant startedDate) {
            this.startedDate = startedDate;
            return this;
        }

        /**
         * Sets when the producing action's persist phase committed.
         *
         * @param completionDate the action completion instant.
         * @return this builder, for chaining.
         */
        public Builder completionDate(Instant completionDate) {
            this.completionDate = completionDate;
            return this;
        }

        /**
         * Sets the affected model's primary identifier.
         *
         * @param modelId affected model's primary identifier (nullable).
         * @return this builder, for chaining.
         */
        public Builder modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        /**
         * Sets the affected model's class name.
         *
         * @param modelType affected model's simple class name (nullable).
         * @return this builder, for chaining.
         */
        public Builder modelType(String modelType) {
            this.modelType = modelType;
            return this;
        }

        /**
         * Sets the event subtype's class name.
         *
         * @param eventType event subtype's simple class name (nullable).
         * @return this builder, for chaining.
         */
        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        /**
         * Sets the event payload.
         *
         * @param payload event payload as JSON (nullable).
         * @return this builder, for chaining.
         */
        public Builder payload(ObjectNode payload) {
            this.payload = payload;
            return this;
        }

        /**
         * Sets when the event was logically produced.
         *
         * @param eventDate the event's own timestamp.
         * @return this builder, for chaining.
         */
        public Builder eventDate(Instant eventDate) {
            this.eventDate = eventDate;
            return this;
        }

        /**
         * Sets the delivery flag.
         *
         * @param delivered whether the EventHandler delivery has marked this row delivered.
         * @return this builder, for chaining.
         */
        public Builder delivered(boolean delivered) {
            this.delivered = delivered;
            return this;
        }

        /** {@return a configured {@link EventEntity}} */
        public EventEntity build() {
            return new EventEntity(this);
        }
    }
}
