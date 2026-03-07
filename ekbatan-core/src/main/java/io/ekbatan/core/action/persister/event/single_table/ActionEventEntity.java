package io.ekbatan.core.action.persister.event.single_table;

import static io.ekbatan.core.action.persister.event.single_table.ActionEventEntity.Builder.actionEventEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.Validate;
import tools.jackson.databind.node.ObjectNode;

class ActionEventEntity {

    public final UUID id;
    public final Instant startedDate;
    public final Instant completionDate;
    public final String actionName;
    public final List<ModelEventEmbedded> modelEvents;
    public final ObjectNode actionParams;

    private ActionEventEntity(Builder builder) {
        this.id = Validate.notNull(builder.id, "id cannot be null");
        this.startedDate = Validate.notNull(builder.startedDate, "startedDate cannot be null");
        this.completionDate = Validate.notNull(builder.completionDate, "completionDate cannot be null");
        this.actionName = Validate.notNull(builder.actionName, "actionName cannot be null");
        this.modelEvents = Validate.notNull(builder.modelEvents, "modelEvents cannot be null");
        this.actionParams = Validate.notNull(builder.actionParams, "actionParams cannot be null");
    }

    static Builder createActionEventEntity(
            UUID id,
            Instant startedDate,
            Instant completionDate,
            String actionName,
            List<ModelEventEmbedded> modelEvents,
            ObjectNode actionParams) {
        return actionEventEntity()
                .id(id)
                .startedDate(startedDate)
                .completionDate(completionDate)
                .actionName(actionName)
                .modelEvents(modelEvents)
                .actionParams(actionParams);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;
        final var that = (ActionEventEntity) other;
        return id.equals(that.id)
                && startedDate.equals(that.startedDate)
                && completionDate.equals(that.completionDate)
                && actionName.equals(that.actionName)
                && CollectionUtils.isEqualCollection(modelEvents, that.modelEvents)
                && actionParams.equals(that.actionParams);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    static final class Builder {
        private UUID id;
        private Instant startedDate;
        private Instant completionDate;
        private String actionName;
        private List<ModelEventEmbedded> modelEvents = List.of();
        private ObjectNode actionParams;

        private Builder() {}

        public static Builder actionEventEntity() {
            return new Builder();
        }

        public Builder id(UUID id) {
            this.id = id;
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

        public Builder actionName(String actionName) {
            this.actionName = actionName;
            return this;
        }

        public Builder modelEvents(List<ModelEventEmbedded> modelEvents) {
            this.modelEvents = modelEvents;
            return this;
        }

        public Builder actionParams(ObjectNode actionParams) {
            this.actionParams = actionParams;
            return this;
        }

        public ActionEventEntity build() {
            return new ActionEventEntity(this);
        }
    }
}
