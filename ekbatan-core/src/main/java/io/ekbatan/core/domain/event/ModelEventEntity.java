package io.ekbatan.core.domain.event;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.Validate;

public class ModelEventEntity {

    public final UUID id;
    public final UUID actionId;
    public final String modelId;
    public final String modelType;
    public final String eventType;
    public final ObjectNode payload;
    public final Instant eventData;

    private ModelEventEntity(Builder builder) {
        this.id = Validate.notNull(builder.id, "id cannot be null");
        this.actionId = Validate.notNull(builder.actionId, "actionId cannot be null");
        this.modelId = Validate.notNull(builder.modelId, "modelId cannot be null");
        this.modelType = Validate.notNull(builder.modelType, "modelType cannot be null");
        this.eventType = Validate.notNull(builder.eventType, "eventType cannot be null");
        this.payload = Validate.notNull(builder.payload, "payload cannot be null");
        this.eventData = Validate.notNull(builder.eventData, "eventData cannot be null");
    }

    public static Builder createModelEventEntity(
            UUID id,
            UUID actionId,
            String modelId,
            String modelType,
            String eventType,
            ObjectNode payload,
            Instant eventData) {
        return new Builder()
                .id(id)
                .actionId(actionId)
                .modelId(modelId)
                .modelType(modelType)
                .eventType(eventType)
                .payload(payload)
                .eventData(eventData);
    }

    public Builder copy() {
        return new Builder()
                .id(id)
                .actionId(actionId)
                .modelId(modelId)
                .modelType(modelType)
                .eventType(eventType)
                .payload(payload)
                .eventData(eventData);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;
        final var modelEventEntity = (ModelEventEntity) other;
        return id.equals(modelEventEntity.id)
                && actionId.equals(modelEventEntity.actionId)
                && modelId.equals(modelEventEntity.modelId)
                && modelType.equals(modelEventEntity.modelType)
                && eventType.equals(modelEventEntity.eventType)
                && payload.equals(modelEventEntity.payload)
                && eventData.equals(modelEventEntity.eventData);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    public static final class Builder {
        private UUID id;
        private UUID actionId;
        private String modelId;
        private String modelType;
        private String eventType;
        private ObjectNode payload;
        private Instant eventData;

        private Builder() {}

        public static Builder modelEventEntity() {
            return new Builder();
        }

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder actionId(UUID actionId) {
            this.actionId = actionId;
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

        public Builder eventData(Instant eventData) {
            this.eventData = eventData;
            return this;
        }

        public ModelEventEntity build() {
            return new ModelEventEntity(this);
        }
    }
}
