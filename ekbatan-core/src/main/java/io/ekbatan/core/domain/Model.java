package io.ekbatan.core.domain;

import static java.time.temporal.ChronoUnit.MICROS;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.commons.lang3.Validate;

/**
 * @param <MODEL> The type of the model
 * @param <ID>    The type of the ID that uniquely identifies the model
 * @param <STATE> The type of the state enum (defaults to GenericState)
 */
public abstract class Model<MODEL extends Model<MODEL, ID, STATE>, ID extends Comparable<?>, STATE extends Enum<STATE>>
        implements Identifiable<ID> {
    public final ID id;
    protected final List<ModelEvent<MODEL>> events;
    public final STATE state;
    public final Instant createdDate;
    public final Instant updatedDate;

    protected <B extends Builder<ID, B, MODEL, STATE>> Model(Builder<ID, B, MODEL, STATE> builder) {
        this.id = Validate.notNull(builder.id, "id cannot be null");
        this.events = Collections.unmodifiableList(Validate.notNull(builder.events, "events cannot be null"));
        this.state = Validate.notNull(builder.state, "state cannot be null");
        this.createdDate = builder.createdDate != null
                ? builder.createdDate
                : Instant.now().truncatedTo(MICROS);
        this.updatedDate = builder.updatedDate != null ? builder.updatedDate : this.createdDate;
    }

    @Override
    public ID getId() {
        return id;
    }

    /**
     * Returns true if this model has been modified since it was loaded.
     */
    public boolean isDirty() {
        return !events.isEmpty();
    }

    public abstract static class Builder<
            ID extends Comparable<?>,
            B extends Builder<ID, B, M, STATE>,
            M extends Model<M, ID, STATE>,
            STATE extends Enum<STATE>> {

        protected ID id;
        protected List<ModelEvent<M>> events = new ArrayList<>();
        protected STATE state;
        protected Instant createdDate;
        protected Instant updatedDate;

        protected Builder() {}

        @SuppressWarnings("unchecked")
        protected final B self() {
            return (B) this;
        }

        public B id(ID id) {
            this.id = id;
            return self();
        }

        /**
         * Adds an event to be raised when this model is saved.
         * @param event The event to raise
         * @return This builder for method chaining
         */
        public B withEvent(ModelEvent<M> event) {
            this.events.add(event);
            return self();
        }

        /**
         * Sets the state of the model.
         *
         * @param state the state to set
         * @return this builder for method chaining
         */
        public B state(STATE state) {
            this.state = state;
            return self();
        }

        public B createdDate(Instant createdDate) {
            this.createdDate = createdDate != null ? createdDate.truncatedTo(MICROS) : null;
            return self();
        }

        public B updatedDate(Instant updatedDate) {
            this.updatedDate = updatedDate != null ? updatedDate.truncatedTo(MICROS) : null;
            return self();
        }

        /**
         * Sets the events for this model.
         *
         * @param events the list of events to set
         * @return this builder for method chaining
         */
        public B events(List<ModelEvent<M>> events) {
            this.events = new ArrayList<>(events);
            return self();
        }

        public abstract M build();

        public B copyBase(M model) {
            return self().id(model.id)
                    .state(model.state)
                    .createdDate(model.createdDate)
                    .updatedDate(model.updatedDate)
                    .events(new ArrayList<>(model.events));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Model<?, ?, ?> model = (Model<?, ?, ?>) o;
        return id.equals(model.id)
                && state == model.state
                && createdDate.equals(model.createdDate)
                && updatedDate.equals(model.updatedDate);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
