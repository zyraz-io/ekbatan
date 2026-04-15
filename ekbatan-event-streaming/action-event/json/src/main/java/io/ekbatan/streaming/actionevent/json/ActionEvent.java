package io.ekbatan.streaming.actionevent.json;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

/**
 * A complete representation of an event from the outbox — mirrors all fields from the outbox row.
 * The consumer gets the full picture and picks what they need.
 * The payload is raw JSON — the consumer deserializes it into whatever type they want.
 */
public class ActionEvent {

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

    public ActionEvent(
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
        this.id = id;
        this.namespace = namespace;
        this.actionId = actionId;
        this.actionName = actionName;
        this.actionParams = actionParams;
        this.startedDate = startedDate;
        this.completionDate = completionDate;
        this.modelId = modelId;
        this.modelType = modelType;
        this.eventType = eventType;
        this.payload = payload;
        this.eventDate = eventDate;
    }
}
