package io.ekbatan.events.streaming.actionevent.json;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;
import tools.jackson.databind.node.ObjectNode;

/**
 * A complete representation of an event from the outbox - mirrors all fields from the outbox row.
 * The consumer gets the full picture and picks what they need.
 * The payload is raw JSON - the consumer deserializes it into whatever type they want.
 */
@JsonDeserialize(builder = ActionEvent.Builder.class)
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

    // Private: callers use actionEvent(), and Jackson goes through the builder as well. Each
    // parameter is documented on its public field and again on its builder setter, so a third copy
    // of the same text here would only be somewhere for the three to drift apart.
    //
    // This was public, carrying @JsonCreator and thirteen @JsonProperty annotations - the minimum
    // needed to make the type deserializable at all, since the fields are final, there is no
    // no-arg constructor, and the jar is not built with -parameters, leaving Jackson no way to
    // match JSON keys to arguments. Deserializing through the builder covers the same ground and
    // removes the reason to expose thirteen positional parameters, among them two adjacent Strings
    // and two adjacent Instants that could be transposed without the compiler noticing.
    private ActionEvent(
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

    /** {@return a fresh builder for {@link ActionEvent}} */
    public static Builder actionEvent() {
        return new Builder();
    }

    /**
     * The only way to construct an {@link ActionEvent}, and the shape Jackson deserializes through.
     *
     * <p>Reading raw CDC output means mapping the message onto this type by hand: a Debezium topic
     * carries snake_case column names, JSON columns as strings rather than nested objects, and
     * timestamps as bare epoch microseconds, so a consumer maps the message across itself rather
     * than calling {@code readValue}. {@code RetryingEventConsumer} in the event-pipeline
     * integration tests is a worked example.
     *
     * <p>Thirteen positional arguments made that mapping easy to get silently wrong:
     * {@code modelType} and {@code eventType} are adjacent {@code String}s, {@code startedDate} and
     * {@code completionDate} adjacent {@code Instant}s, and {@code actionParams} and
     * {@code payload} are both {@code ObjectNode}. Transpose any of those pairs and it compiles,
     * runs, and produces an event routed to the wrong topic or carrying the wrong body.
     *
     * <p>Nothing is validated here. Several fields are legitimately absent - {@code modelId},
     * {@code modelType}, {@code eventType} and {@code payload} are all null on the sentinel row an
     * action with no model event writes - so a wire type that rejected them would reject valid
     * traffic. The builder removes the ordering hazard; it does not add a contract.
     */
    @JsonPOJOBuilder(withPrefix = "")
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
        private boolean delivered;

        private Builder() {}

        /**
         * Sets the event's stable identifier, matching the outbox row's primary key.
         *
         * @param id stable per-event identifier.
         * @return this builder, for chaining.
         */
        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        /**
         * Sets the logical namespace the event was recorded under.
         *
         * @param namespace logical namespace recorded with the event.
         * @return this builder, for chaining.
         */
        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        /**
         * Sets the identifier shared by every event from one action invocation.
         *
         * @param actionId identifier of the producing action invocation.
         * @return this builder, for chaining.
         */
        public Builder actionId(UUID actionId) {
            this.actionId = actionId;
            return this;
        }

        /**
         * Sets the producing action's simple class name.
         *
         * @param actionName producing action's simple class name.
         * @return this builder, for chaining.
         */
        public Builder actionName(String actionName) {
            this.actionName = actionName;
            return this;
        }

        /**
         * Sets the parameters the action was invoked with.
         *
         * @param actionParams parameters the action was invoked with.
         * @return this builder, for chaining.
         */
        public Builder actionParams(ObjectNode actionParams) {
            this.actionParams = actionParams;
            return this;
        }

        /**
         * Sets when the action invocation began.
         *
         * @param startedDate when the action invocation started.
         * @return this builder, for chaining.
         */
        public Builder startedDate(Instant startedDate) {
            this.startedDate = startedDate;
            return this;
        }

        /**
         * Sets when the action invocation committed.
         *
         * @param completionDate when the action invocation committed.
         * @return this builder, for chaining.
         */
        public Builder completionDate(Instant completionDate) {
            this.completionDate = completionDate;
            return this;
        }

        /**
         * Sets the affected model's primary identifier.
         *
         * @param modelId primary identifier of the affected model; null when the action targets no
         *     single model.
         * @return this builder, for chaining.
         */
        public Builder modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        /**
         * Sets the affected model's simple class name.
         *
         * @param modelType simple class name of the affected model; null alongside a null modelId.
         * @return this builder, for chaining.
         */
        public Builder modelType(String modelType) {
            this.modelType = modelType;
            return this;
        }

        /**
         * Sets the event subclass's simple class name.
         *
         * @param eventType simple class name of the event subclass; null on a sentinel row.
         * @return this builder, for chaining.
         */
        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        /**
         * Sets the event payload, carried as raw JSON.
         *
         * @param payload event payload as raw JSON; null on a sentinel row.
         * @return this builder, for chaining.
         */
        public Builder payload(ObjectNode payload) {
            this.payload = payload;
            return this;
        }

        /**
         * Sets when the event was logically produced.
         *
         * @param eventDate when the event was logically produced.
         * @return this builder, for chaining.
         */
        public Builder eventDate(Instant eventDate) {
            this.eventDate = eventDate;
            return this;
        }

        /**
         * Sets whether the pipeline has already forwarded the outbox row.
         *
         * @param delivered whether the outbox row has been forwarded by the pipeline.
         * @return this builder, for chaining.
         */
        public Builder delivered(boolean delivered) {
            this.delivered = delivered;
            return this;
        }

        /** {@return a configured {@link ActionEvent}} */
        public ActionEvent build() {
            return new ActionEvent(
                    id,
                    namespace,
                    actionId,
                    actionName,
                    actionParams,
                    startedDate,
                    completionDate,
                    modelId,
                    modelType,
                    eventType,
                    payload,
                    eventDate,
                    delivered);
        }
    }
}
