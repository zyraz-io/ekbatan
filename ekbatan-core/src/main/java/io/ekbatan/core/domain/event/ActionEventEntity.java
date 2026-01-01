package io.ekbatan.core.domain.event;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.Validate;

public class ActionEventEntity {

    public final UUID id;
    public final Instant startedDate;
    public final Instant completionDate;
    public final String actionName;
    public final ObjectNode actionParams;

    private ActionEventEntity(Builder builder) {
        this.id = Validate.notNull(builder.id, "id cannot be null");
        this.startedDate = Validate.notNull(builder.startedDate, "startedDate cannot be null");
        this.completionDate = Validate.notNull(builder.completionDate, "completionDate cannot be null");
        this.actionName = Validate.notNull(builder.actionName, "actionName cannot be null");
        this.actionParams = Validate.notNull(builder.actionParams, "actionParams cannot be null");
    }

    public static Builder createActionEventEntity(
            UUID id, Instant startedDate, Instant completionDate, String actionName, ObjectNode actionParams) {
        return new Builder()
                .id(id)
                .startedDate(startedDate)
                .completionDate(completionDate)
                .actionName(actionName)
                .actionParams(actionParams);
    }

    public Builder copy() {
        return new Builder()
                .id(id)
                .startedDate(startedDate)
                .completionDate(completionDate)
                .actionName(actionName)
                .actionParams(actionParams);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;
        final var actionEventEntity = (ActionEventEntity) other;
        return id.equals(actionEventEntity.id)
                && startedDate.equals(actionEventEntity.startedDate)
                && completionDate.equals(actionEventEntity.completionDate)
                && actionName.equals(actionEventEntity.actionName)
                && actionParams.equals(actionEventEntity.actionParams);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    public static final class Builder {
        private UUID id;
        private Instant startedDate;
        private Instant completionDate;
        private String actionName;
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

        public Builder actionParams(ObjectNode actionParams) {
            this.actionParams = actionParams;
            return this;
        }

        public ActionEventEntity build() {
            return new ActionEventEntity(this);
        }
    }
}
