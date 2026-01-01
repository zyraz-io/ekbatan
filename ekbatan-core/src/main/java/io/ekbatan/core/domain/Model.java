package io.ekbatan.core.domain;

import static java.time.temporal.ChronoUnit.MICROS;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.commons.lang3.Validate;

public abstract class Model<MODEL extends Model<MODEL, ID, STATE>, ID extends Comparable<?>, STATE extends Enum<STATE>>
        implements Persistable<ID> {
    public final ID id;
    public final List<ModelEvent<MODEL>> events;
    public final STATE state;
    public final Instant createdDate;
    public final Instant updatedDate;
    public final Long version;

    protected <B extends Builder<ID, B, MODEL, STATE>> Model(Builder<ID, B, MODEL, STATE> builder) {
        this.id = Validate.notNull(builder.id, "id cannot be null");
        this.events = Collections.unmodifiableList(Validate.notNull(builder.events, "events cannot be null"));
        this.state = Validate.notNull(builder.state, "state cannot be null");
        this.createdDate = builder.createdDate != null
                ? builder.createdDate
                : Instant.now().truncatedTo(MICROS);
        this.updatedDate = builder.updatedDate != null ? builder.updatedDate : this.createdDate;
        this.version = Validate.notNull(builder.version, "version cannot be null");
        Validate.isTrue(builder.version >= 1, "version must be greater than or equal to 1");
    }

    @Override
    public ID getId() {
        return id;
    }

    @Override
    public Long getVersion() {
        return version;
    }

    @Override
    public final boolean isModel() {
        return true;
    }

    public abstract Builder<ID, ?, MODEL, STATE> copy();

    @Override
    @SuppressWarnings("unchecked")
    public <E extends Persistable<ID>> E nextVersion() {
        return (E) copy().increaseVersion().build();
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
        protected Long version;

        protected Builder() {}

        @SuppressWarnings("unchecked")
        protected final B self() {
            return (B) this;
        }

        public B id(ID id) {
            this.id = id;
            return self();
        }

        public B withEvent(ModelEvent<M> event) {
            this.events.add(event);
            return self();
        }

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

        public B withInitialVersion() {
            this.version = 1L;
            return self();
        }

        public B version(Long version) {
            this.version = version;
            return self();
        }

        public B increaseVersion() {
            this.version++;
            return self();
        }

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
                    .version(model.version)
                    .events(new ArrayList<>(model.events));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Model<?, ?, ?> model = (Model<?, ?, ?>) o;
        return id.equals(model.id)
                && state.equals(model.state)
                && version.equals(model.version)
                && createdDate.equals(model.createdDate)
                && updatedDate.equals(model.updatedDate);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
