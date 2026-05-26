package io.ekbatan.events.streaming.actionevent.json;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

/**
 * A complete representation of an event from the outbox - mirrors all fields from the outbox row.
 * The consumer gets the full picture and picks what they need.
 * The payload is raw JSON - the consumer deserializes it into whatever type they want.
 */
public class ActionEvent {

    /** Stable per-event identifier (matches the outbox row's primary key). */
    public final UUID id;

    /** Logical namespace recorded with the event - lets multiple deployments share an eventlog table without collisions. */
    public final String namespace;

    /** Identifier of the action invocation that produced this event. Same value across every event emitted by one action call. */
    public final UUID actionId;

    /** Simple class name of the action that produced the event (e.g. {@code WidgetCreateAction}). */
    public final String actionName;

    /** Serialized parameters the action was invoked with, captured at action start. */
    public final ObjectNode actionParams;

    /** Instant the action invocation started. */
    public final Instant startedDate;

    /** Instant the action invocation committed (becomes equal to the eventlog row's transaction commit time). */
    public final Instant completionDate;

    /** String form of the primary identifier of the model the event is about; {@code null} for actions that don't target a single model. */
    public final String modelId;

    /** Simple class name of the affected model (e.g. {@code Widget}); {@code null} when {@link #modelId} is {@code null}. */
    public final String modelType;

    /** Simple class name of the event subclass (e.g. {@code WidgetCreated}); {@code null} for actions that emitted no model event. */
    public final String eventType;

    /** The event payload as JSON - consumers deserialize into their own DTO/POJO of choice. */
    public final ObjectNode payload;

    /** Instant the event itself was logically produced inside the action (may slightly precede {@link #completionDate}). */
    public final Instant eventDate;

    /** Whether the eventlog row has already been forwarded by the configured pipeline (set true by the producer-side dispatcher). */
    public final boolean delivered;

    /**
     * All-args constructor used by Jackson during JSON deserialization on the consumer side.
     *
     * @param id stable per-event identifier.
     * @param namespace logical namespace (typically the producer application identifier).
     * @param actionId identifier of the producing action invocation.
     * @param actionName producing action's simple class name.
     * @param actionParams parameters the action was invoked with.
     * @param startedDate when the action invocation started.
     * @param completionDate when the action invocation committed.
     * @param modelId primary identifier of the affected model (nullable).
     * @param modelType simple class name of the affected model (nullable).
     * @param eventType simple class name of the event subclass (nullable).
     * @param payload event payload as a Jackson {@link ObjectNode}.
     * @param eventDate when the event was logically produced.
     * @param delivered whether the eventlog row has been forwarded by the pipeline.
     */
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
            Instant eventDate,
            boolean delivered) {
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
        this.delivered = delivered;
    }
}
