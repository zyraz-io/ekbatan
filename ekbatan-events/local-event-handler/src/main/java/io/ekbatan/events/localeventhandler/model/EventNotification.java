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
 * job reads this entire row and invokes the handler without a second query — no JOIN,
 * no hydration round-trip.
 *
 * <p>Not a {@code Model} or {@code Entity} in the framework sense — no version field, no
 * soft-delete state. The dispatch job is the sole writer; rows transition through
 * {@link EventNotificationState} via direct UPDATEs.
 */
public final class EventNotification {

    // notification identity + retry/lifecycle state
    public final UUID id;
    public final UUID eventId;
    public final String handlerName;
    public final EventNotificationState state;
    public final int attempts;
    public final Instant nextRetryAt;
    public final Instant createdDate;
    public final Instant updatedDate;

    // denormalized event + action context — copied from eventlog.events at fan-out time
    public final String namespace;
    public final UUID actionId;
    public final String actionName;
    public final ObjectNode actionParams;
    public final Instant startedDate;
    public final Instant completionDate;
    public final String modelId;
    public final String modelType;
    public final String eventType;
    public final ObjectNode payload;
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

    public static Builder eventNotification() {
        return new Builder();
    }

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

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder eventId(UUID eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder handlerName(String handlerName) {
            this.handlerName = handlerName;
            return this;
        }

        public Builder state(EventNotificationState state) {
            this.state = state;
            return this;
        }

        public Builder attempts(int attempts) {
            this.attempts = attempts;
            return this;
        }

        public Builder nextRetryAt(Instant nextRetryAt) {
            this.nextRetryAt = nextRetryAt;
            return this;
        }

        public Builder createdDate(Instant createdDate) {
            this.createdDate = createdDate;
            return this;
        }

        public Builder updatedDate(Instant updatedDate) {
            this.updatedDate = updatedDate;
            return this;
        }

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder actionId(UUID actionId) {
            this.actionId = actionId;
            return this;
        }

        public Builder actionName(String actionName) {
            this.actionName = actionName;
            return this;
        }

        public Builder actionParams(ObjectNode actionParams) {
            this.actionParams = actionParams;
            return this;
        }

        public Builder startedDate(Instant startedDate) {
            this.startedDate = startedDate;
            return this;
        }

        public Builder completionDate(Instant completionDate) {
            this.completionDate = completionDate;
            return this;
        }

        public Builder modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        public Builder modelType(String modelType) {
            this.modelType = modelType;
            return this;
        }

        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder payload(ObjectNode payload) {
            this.payload = payload;
            return this;
        }

        public Builder eventDate(Instant eventDate) {
            this.eventDate = eventDate;
            return this;
        }

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
