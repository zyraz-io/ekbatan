package io.ekbatan.core.action.persister.event.single_table_json;

import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.Validate;
import tools.jackson.databind.node.ObjectNode;

class EventEntity {

    public final UUID id;
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
    }

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

    static final class Builder {
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

        private Builder() {}

        public Builder id(UUID id) {
            this.id = id;
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

        public EventEntity build() {
            return new EventEntity(this);
        }
    }
}
