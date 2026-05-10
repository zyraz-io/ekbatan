package io.ekbatan.core.domain;

import java.io.Serializable;
import org.apache.commons.lang3.Validate;

/**
 * A domain event emitted by a {@link Model}. Subclasses describe state transitions worth
 * recording in the event stream — created, deposited, transferred, closed, etc.
 *
 * <p>Events are attached to a {@code Model} via the builder's {@code withEvent(...)} method;
 * {@code ChangePersister} extracts them when the action commits and persists them through the
 * configured {@link io.ekbatan.core.action.persister.event.EventPersister}. The persistence
 * row carries {@link #modelId} and {@link #modelName} so downstream consumers can correlate
 * events back to their source aggregate without having to inspect the payload.
 *
 * <p>Concrete subclasses are typically simple data carriers — records or hand-written value
 * classes — that serialize cleanly to JSON / Avro / Protobuf via the wire-format modules
 * (e.g. {@code ekbatan-action-event-json}). Mutable state, transient references, and circular
 * graphs do not belong in events.
 *
 * @param <MODEL> the model class that emits this event
 */
public abstract class ModelEvent<MODEL> implements Serializable {

    /** String form of the model id this event was emitted from. */
    public final String modelId;

    /** Simple class name of the model class this event was emitted from. */
    public final String modelName;

    /**
     * Subclass constructor — captures the source model's identifier and class name.
     *
     * @param modelId string form of the source model's primary identifier.
     * @param modelClass class of the source model (used for {@link #modelName}).
     */
    protected ModelEvent(String modelId, Class<MODEL> modelClass) {
        this.modelId = Validate.notBlank(modelId, "modelId cannot be null or blank");
        this.modelName =
                Validate.notNull(modelClass, "modelClass cannot be null").getSimpleName();
    }
}
