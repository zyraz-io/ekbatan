package io.ekbatan.core.action.persister.event.single_table;

import static io.ekbatan.core.action.persister.event.single_table.ModelEventEmbedded.Builder.modelEventEmbedded;

import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.Validate;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;
import tools.jackson.databind.node.ObjectNode;

@JsonDeserialize(builder = ModelEventEmbedded.Builder.class)
class ModelEventEmbedded {

    public final UUID id;
    public final UUID actionId;
    public final String modelId;
    public final String modelType;
    public final String eventType;
    public final ObjectNode payload;
    public final Instant eventDate;

    private ModelEventEmbedded(Builder builder) {
        this.id = Validate.notNull(builder.id, "id cannot be null");
        this.actionId = Validate.notNull(builder.actionId, "actionId cannot be null");
        this.modelId = Validate.notNull(builder.modelId, "modelId cannot be null");
        this.modelType = Validate.notNull(builder.modelType, "modelType cannot be null");
        this.eventType = Validate.notNull(builder.eventType, "eventType cannot be null");
        this.payload = Validate.notNull(builder.payload, "payload cannot be null");
        this.eventDate = Validate.notNull(builder.eventDate, "eventDate cannot be null");
    }

    static Builder createModelEventEmbedded(
            UUID id,
            UUID actionId,
            String modelId,
            String modelType,
            String eventType,
            ObjectNode payload,
            Instant eventDate) {
        return modelEventEmbedded()
                .id(id)
                .actionId(actionId)
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
        final var that = (ModelEventEmbedded) other;
        return id.equals(that.id)
                && actionId.equals(that.actionId)
                && modelId.equals(that.modelId)
                && modelType.equals(that.modelType)
                && eventType.equals(that.eventType)
                && payload.equals(that.payload)
                && eventDate.equals(that.eventDate);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @JsonPOJOBuilder(withPrefix = "")
    static final class Builder {
        private UUID id;
        private UUID actionId;
        private String modelId;
        private String modelType;
        private String eventType;
        private ObjectNode payload;
        private Instant eventDate;

        private Builder() {}

        public static Builder modelEventEmbedded() {
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

        public Builder eventDate(Instant eventDate) {
            this.eventDate = eventDate;
            return this;
        }

        public ModelEventEmbedded build() {
            return new ModelEventEmbedded(this);
        }
    }
}
