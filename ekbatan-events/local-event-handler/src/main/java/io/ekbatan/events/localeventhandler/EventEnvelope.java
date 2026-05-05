package io.ekbatan.events.localeventhandler;

import io.ekbatan.core.domain.ModelEvent;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.Validate;
import tools.jackson.databind.node.ObjectNode;

/**
 * The dispatch-side envelope an {@link EventHandler} receives. Carries the typed
 * {@link ModelEvent} payload alongside the surrounding action context so a handler can read
 * both the event itself and the action that produced it without ever touching framework-
 * internal queue state (notification id, retry attempts, next-retry time, etc.).
 *
 * <p>Built once per delivery by {@code EventHandlingJob}, immediately before invoking the
 * handler. The field set mirrors the wire-side {@code ActionEvent} envelope published by
 * the {@code streaming/action-event:json} module — same conceptual shape, just typed.
 *
 * @param <E> the {@link ModelEvent} subtype the receiving handler subscribes to
 */
public final class EventEnvelope<E extends ModelEvent<?>> {

    /** The typed event payload. */
    public final E event;

    /** {@code eventlog.events.id} of the source event row. Stable across retries — usable as an idempotency key. */
    public final UUID eventId;

    /** Service identifier set on {@code ActionExecutor.namespace(...)}. */
    public final String namespace;

    /** {@code eventlog.events.action_id} — same UUID across all events emitted by the same action. */
    public final UUID actionId;

    /** Simple class name of the action that emitted the event, e.g. {@code "WalletDepositAction"}. */
    public final String actionName;

    /** The action's {@code Params} record, serialized to JSON. */
    public final ObjectNode actionParams;

    /** When the action's {@code perform()} began. */
    public final Instant startedDate;

    /** When the action's persist phase committed. */
    public final Instant completionDate;

    /** The affected model's id (sentinel rows have null, but they don't reach handlers). */
    public final String modelId;

    /** Simple class name of the affected model, e.g. {@code "Wallet"}. */
    public final String modelType;

    /** The event's own timestamp (typically equal to {@link #completionDate}). */
    public final Instant eventDate;

    private EventEnvelope(Builder<E> builder) {
        this.event = Validate.notNull(builder.event, "event cannot be null");
        this.eventId = Validate.notNull(builder.eventId, "eventId cannot be null");
        this.namespace = Validate.notNull(builder.namespace, "namespace cannot be null");
        this.actionId = Validate.notNull(builder.actionId, "actionId cannot be null");
        this.actionName = Validate.notNull(builder.actionName, "actionName cannot be null");
        this.actionParams = Validate.notNull(builder.actionParams, "actionParams cannot be null");
        this.startedDate = Validate.notNull(builder.startedDate, "startedDate cannot be null");
        this.completionDate = Validate.notNull(builder.completionDate, "completionDate cannot be null");
        this.modelId = builder.modelId;
        this.modelType = builder.modelType;
        this.eventDate = Validate.notNull(builder.eventDate, "eventDate cannot be null");
    }

    public static final class Builder<E extends ModelEvent<?>> {

        private E event;
        private UUID eventId;
        private String namespace;
        private UUID actionId;
        private String actionName;
        private ObjectNode actionParams;
        private Instant startedDate;
        private Instant completionDate;
        private String modelId;
        private String modelType;
        private Instant eventDate;

        private Builder() {}

        public static <T extends ModelEvent<?>> Builder<T> eventEnvelope() {
            return new Builder<>();
        }

        public Builder<E> event(E event) {
            this.event = event;
            return this;
        }

        public Builder<E> eventId(UUID eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder<E> namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder<E> actionId(UUID actionId) {
            this.actionId = actionId;
            return this;
        }

        public Builder<E> actionName(String actionName) {
            this.actionName = actionName;
            return this;
        }

        public Builder<E> actionParams(ObjectNode actionParams) {
            this.actionParams = actionParams;
            return this;
        }

        public Builder<E> startedDate(Instant startedDate) {
            this.startedDate = startedDate;
            return this;
        }

        public Builder<E> completionDate(Instant completionDate) {
            this.completionDate = completionDate;
            return this;
        }

        public Builder<E> modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        public Builder<E> modelType(String modelType) {
            this.modelType = modelType;
            return this;
        }

        public Builder<E> eventDate(Instant eventDate) {
            this.eventDate = eventDate;
            return this;
        }

        public EventEnvelope<E> build() {
            return new EventEnvelope<>(this);
        }
    }
}
