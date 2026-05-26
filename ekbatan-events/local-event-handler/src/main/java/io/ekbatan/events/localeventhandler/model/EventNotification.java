package io.ekbatan.events.localeventhandler.model;

import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.Validate;
import tools.jackson.databind.node.ObjectNode;

/**
 * Immutable in-memory representation of a single {@code eventlog.event_notifications} row.
 *
 * <p>Carries the dispatch-side delivery state plus a denormalized copy of the event and
 * action context the fan-out job snapshotted from {@code eventlog.events}. The dispatch
 * job reads this entire row and invokes the handler without a second query - no JOIN,
 * no hydration round-trip.
 *
 * <p>Not a {@code Model} or {@code Entity} in the framework sense - no version field, no
 * soft-delete state. The dispatch job is the sole writer; rows transition through
 * {@link EventNotificationState} via direct UPDATEs.
 */
public final class EventNotification {

    // notification identity + retry/lifecycle state

    /** Stable per-notification identifier (primary key in {@code event_notifications}). */
    public final UUID id;

    /** {@code eventlog.events.id} of the source event - same value across all handler notifications for one event. */
    public final UUID eventId;

    /** Cluster-stable handler name (see {@link io.ekbatan.events.localeventhandler.EventHandler#name}). */
    public final String handlerName;

    /** Lifecycle state of this delivery (PENDING / SUCCEEDED / EXPIRED). */
    public final EventNotificationState state;

    /** Number of delivery attempts so far; incremented on each failure. */
    public final int attempts;

    /** Earliest wall-clock instant the dispatch job may invoke the handler again. */
    public final Instant nextRetryAt;

    /** When the notification row was created by the fan-out job. */
    public final Instant createdDate;

    /** When the notification row was last updated by the dispatch job. */
    public final Instant updatedDate;

    // denormalized event + action context - copied from eventlog.events at fan-out time

    /** Service namespace (copied from the source event). */
    public final String namespace;

    /** Identifier of the producing action invocation (copied from the source event). */
    public final UUID actionId;

    /** Simple class name of the producing action (copied from the source event). */
    public final String actionName;

    /** Serialized action parameters (copied from the source event). */
    public final ObjectNode actionParams;

    /** When the producing action's {@code perform()} began (copied from the source event). */
    public final Instant startedDate;

    /** When the producing action's persist phase committed (copied from the source event). */
    public final Instant completionDate;

    /** Primary identifier of the affected model (nullable, copied from the source event). */
    public final String modelId;

    /** Simple class name of the affected model (nullable, copied from the source event). */
    public final String modelType;

    /** Simple class name of the event subtype (used for handler routing). */
    public final String eventType;

    /** The event payload as JSON (copied from the source event). */
    public final ObjectNode payload;

    /** When the event was logically produced (copied from the source event). */
    public final Instant eventDate;

    private EventNotification(Builder builder) {
        this.id = Validate.notNull(builder.id, "id cannot be null");
        this.eventId = Validate.notNull(builder.eventId, "eventId cannot be null");
        this.handlerName = Validate.notBlank(builder.handlerName, "handlerName cannot be blank");
        this.state = Validate.notNull(builder.state, "state cannot be null");
        this.attempts = builder.attempts;
        this.nextRetryAt = Validate.notNull(builder.nextRetryAt, "nextRetryAt cannot be null");
        this.createdDate = Validate.notNull(builder.createdDate, "createdDate cannot be null");
        this.updatedDate = Validate.notNull(builder.updatedDate, "updatedDate cannot be null");

        this.namespace = Validate.notNull(builder.namespace, "namespace cannot be null");
        this.actionId = Validate.notNull(builder.actionId, "actionId cannot be null");
        this.actionName = Validate.notNull(builder.actionName, "actionName cannot be null");
        this.actionParams = Validate.notNull(builder.actionParams, "actionParams cannot be null");
        this.startedDate = Validate.notNull(builder.startedDate, "startedDate cannot be null");
        this.completionDate = Validate.notNull(builder.completionDate, "completionDate cannot be null");
        this.modelId = builder.modelId;
        this.modelType = builder.modelType;
        this.eventType = Validate.notBlank(builder.eventType, "eventType cannot be blank");
        this.payload = builder.payload;
        this.eventDate = Validate.notNull(builder.eventDate, "eventDate cannot be null");
    }

    /** {@return a fresh builder for {@link EventNotification}} */
    public static Builder eventNotification() {
        return new Builder();
    }

    /** Fluent builder for {@link EventNotification}. Obtain via {@link #eventNotification()}. */
    public static final class Builder {

        private UUID id;
        private UUID eventId;
        private String handlerName;
        private EventNotificationState state = EventNotificationState.PENDING;
        private int attempts = 0;
        private Instant nextRetryAt;
        private Instant createdDate;
        private Instant updatedDate;

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

        private Builder() {}

        /**
         * Sets the notification's primary key.
         *
         * @param id the notification's primary key.
         * @return this builder, for chaining.
         */
        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        /**
         * Sets the source event's id.
         *
         * @param eventId the source event's id.
         * @return this builder, for chaining.
         */
        public Builder eventId(UUID eventId) {
            this.eventId = eventId;
            return this;
        }

        /**
         * Sets the cluster-stable handler name.
         *
         * @param handlerName cluster-stable handler name.
         * @return this builder, for chaining.
         */
        public Builder handlerName(String handlerName) {
            this.handlerName = handlerName;
            return this;
        }

        /**
         * Sets the lifecycle state.
         *
         * @param state lifecycle state.
         * @return this builder, for chaining.
         */
        public Builder state(EventNotificationState state) {
            this.state = state;
            return this;
        }

        /**
         * Sets the delivery attempt count.
         *
         * @param attempts delivery attempt count so far.
         * @return this builder, for chaining.
         */
        public Builder attempts(int attempts) {
            this.attempts = attempts;
            return this;
        }

        /**
         * Sets the earliest next-retry instant.
         *
         * @param nextRetryAt earliest instant the dispatch job may invoke the handler again.
         * @return this builder, for chaining.
         */
        public Builder nextRetryAt(Instant nextRetryAt) {
            this.nextRetryAt = nextRetryAt;
            return this;
        }

        /**
         * Sets the notification row creation time.
         *
         * @param createdDate when the notification row was created by the fan-out job.
         * @return this builder, for chaining.
         */
        public Builder createdDate(Instant createdDate) {
            this.createdDate = createdDate;
            return this;
        }

        /**
         * Sets the notification row last-updated time.
         *
         * @param updatedDate when the notification row was last updated by the dispatch job.
         * @return this builder, for chaining.
         */
        public Builder updatedDate(Instant updatedDate) {
            this.updatedDate = updatedDate;
            return this;
        }

        /**
         * Sets the namespace (copied from the source event).
         *
         * @param namespace service namespace.
         * @return this builder, for chaining.
         */
        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        /**
         * Sets the producing action's id (copied from the source event).
         *
         * @param actionId producing action's id.
         * @return this builder, for chaining.
         */
        public Builder actionId(UUID actionId) {
            this.actionId = actionId;
            return this;
        }

        /**
         * Sets the producing action's class name (copied from the source event).
         *
         * @param actionName producing action's simple class name.
         * @return this builder, for chaining.
         */
        public Builder actionName(String actionName) {
            this.actionName = actionName;
            return this;
        }

        /**
         * Sets the producing action's serialized parameters (copied from the source event).
         *
         * @param actionParams producing action's serialized parameters.
         * @return this builder, for chaining.
         */
        public Builder actionParams(ObjectNode actionParams) {
            this.actionParams = actionParams;
            return this;
        }

        /**
         * Sets when the producing action's perform() began.
         *
         * @param startedDate the action's start instant.
         * @return this builder, for chaining.
         */
        public Builder startedDate(Instant startedDate) {
            this.startedDate = startedDate;
            return this;
        }

        /**
         * Sets when the producing action's persist phase committed.
         *
         * @param completionDate the action's completion instant.
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
         * @param eventType event subtype's simple class name.
         * @return this builder, for chaining.
         */
        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        /**
         * Sets the event payload.
         *
         * @param payload event payload as JSON.
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

        /** {@return a configured {@link EventNotification}; throws if any required field is null/blank} */
        public EventNotification build() {
            return new EventNotification(this);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof EventNotification that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
