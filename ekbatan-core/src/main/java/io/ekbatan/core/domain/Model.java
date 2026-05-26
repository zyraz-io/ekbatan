package io.ekbatan.core.domain;

import static java.time.temporal.ChronoUnit.MICROS;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.Validate;

/**
 * A persistent aggregate that emits domain events. Every state change worth telling the rest
 * of the system about — created, transferred, closed, refunded — gets attached to the
 * {@code Model} as a {@link ModelEvent} on its builder, then atomically persisted by
 * {@code ChangePersister} alongside the domain row when the action commits.
 *
 * <p>This is the framework's primary "things that emit events" shape. The other shape,
 * {@link Entity}, is for state the persistence layer needs to track but whose changes don't
 * belong in the event stream (caches, lookup tables, audit-only side-effects).
 *
 * <h2>Construction and mutation</h2>
 *
 * <p>Subclasses are immutable. A generated {@code *Builder} via {@code @AutoBuilder} (from
 * {@code ekbatan-annotation-processor}) constructs new instances; mutations produce a new
 * instance via {@code copy()...build()}. {@code createdDate} / {@code updatedDate} are
 * truncated to microsecond precision at the constructor — Postgres and MariaDB lose
 * sub-microsecond detail, so the in-memory representation matches what comes back from the DB.
 *
 * <h2>Events</h2>
 *
 * <p>{@link #events} is an unmodifiable list captured at construction time. Build a new
 * version of the model (via {@code copy().withEvent(...).build()}) to attach more events; the
 * framework will not silently re-emit events from a re-read row, so the only way an event
 * enters the eventlog is via an explicit {@code withEvent(...)} call in {@code Action.perform}.
 *
 * <h2>Optimistic locking</h2>
 *
 * <p>{@link #version} is monotonically increasing per row; the repository's {@code UPDATE}
 * carries {@code WHERE id=? AND version=?} and {@link #nextVersion()} delegates to the builder
 * to produce the incremented successor. A stale read raises
 * {@link io.ekbatan.core.repository.exception.StaleRecordException} rather than overwriting.
 *
 * <h2>Equality</h2>
 *
 * <p>Two {@code Model} instances are equal iff their {@code id}, {@code state}, {@code version},
 * {@code createdDate}, and {@code updatedDate} match. Event lists are intentionally excluded:
 * two readers of the same DB row produce equal models even if one has events attached and the
 * other doesn't.
 *
 * @param <MODEL> the concrete subclass (CRTP-style self-type)
 * @param <ID>    the identifier type (usually {@link Id} or {@link ShardedId})
 * @param <STATE> the state-enum type for this model's lifecycle (see {@link GenericState} as a default)
 */
public abstract class Model<MODEL extends Model<MODEL, ID, STATE>, ID extends Comparable<?>, STATE extends Enum<STATE>>
        implements Persistable<ID> {

    /** The model's primary identifier. */
    public final ID id;

    /** Events attached to this model instance; flushed to the eventlog when the action commits. */
    public final List<ModelEvent<MODEL>> events;

    /** The model's lifecycle state. */
    public final STATE state;

    /** When the row was first inserted, truncated to microsecond precision. */
    public final Instant createdDate;

    /** When the row was last updated, truncated to microsecond precision. */
    public final Instant updatedDate;

    /** Optimistic-locking version; incremented on each update. */
    public final Long version;

    /**
     * Subclass constructor invoked by the generated builder's {@code build()}.
     *
     * @param builder the builder carrying validated field values.
     * @param <B> the concrete builder type (CRTP).
     */
    protected <B extends Builder<ID, B, MODEL, STATE>> Model(Builder<ID, B, MODEL, STATE> builder) {
        this.id = Validate.notNull(builder.id, "id cannot be null");
        this.events = List.copyOf(Validate.notNull(builder.events, "events cannot be null"));
        this.state = Validate.notNull(builder.state, "state cannot be null");
        this.createdDate = Validate.notNull(builder.createdDate, "createdDate cannot be null")
                .truncatedTo(MICROS);
        this.updatedDate = builder.updatedDate != null ? builder.updatedDate.truncatedTo(MICROS) : this.createdDate;
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

    /** {@return a new builder pre-populated with this model's field values} */
    public abstract Builder<ID, ?, MODEL, STATE> copy();

    @Override
    @SuppressWarnings("unchecked")
    public <E extends Persistable<ID>> E nextVersion() {
        return (E) copy().increaseVersion().build();
    }

    /**
     * Base fluent builder for {@link Model} subclasses. CRTP-typed self-type so concrete
     * builders can add domain fields and still chain through the base setters.
     *
     * @param <ID> the model's identifier type.
     * @param <B> the concrete builder type (CRTP self-type).
     * @param <M> the concrete model type.
     * @param <STATE> the model's state enum type.
     */
    public abstract static class Builder<
            ID extends Comparable<?>,
            B extends Builder<ID, B, M, STATE>,
            M extends Model<M, ID, STATE>,
            STATE extends Enum<STATE>> {

        /** Builder's accumulated id field. */
        protected ID id;

        /** Builder's accumulated events list. */
        protected List<ModelEvent<M>> events = new ArrayList<>();

        /** Builder's accumulated state field. */
        protected STATE state;

        /** Builder's accumulated createdDate field. */
        protected Instant createdDate;

        /** Builder's accumulated updatedDate field. */
        protected Instant updatedDate;

        /** Builder's accumulated version field. */
        protected Long version;

        /** No-arg constructor for subclasses. */
        protected Builder() {}

        /** {@return this builder, narrowed to the CRTP subtype for fluent chaining} */
        @SuppressWarnings("unchecked")
        protected final B self() {
            return (B) this;
        }

        /**
         * Sets the model's primary identifier.
         *
         * @param id the model's id.
         * @return this builder, for chaining.
         */
        public B id(ID id) {
            this.id = id;
            return self();
        }

        /**
         * Attaches an event to the model — flushed to the eventlog when the action commits.
         *
         * @param event the event to attach.
         * @return this builder, for chaining.
         */
        public B withEvent(ModelEvent<M> event) {
            this.events.add(event);
            return self();
        }

        /**
         * Sets the model's lifecycle state.
         *
         * @param state the state value.
         * @return this builder, for chaining.
         */
        public B state(STATE state) {
            this.state = state;
            return self();
        }

        /**
         * Sets the row's createdDate.
         *
         * @param createdDate the insertion instant.
         * @return this builder, for chaining.
         */
        public B createdDate(Instant createdDate) {
            this.createdDate = createdDate;
            return self();
        }

        /**
         * Sets the row's updatedDate.
         *
         * @param updatedDate the last-modified instant.
         * @return this builder, for chaining.
         */
        public B updatedDate(Instant updatedDate) {
            this.updatedDate = updatedDate;
            return self();
        }

        /**
         * Initializes the version to 1 — used by callers staging a new model for first insert.
         *
         * @return this builder, for chaining.
         */
        public B withInitialVersion() {
            this.version = 1L;
            return self();
        }

        /**
         * Sets the model's optimistic-locking version explicitly.
         *
         * @param version the version value (must be ≥ 1 at build time).
         * @return this builder, for chaining.
         */
        public B version(Long version) {
            this.version = version;
            return self();
        }

        /**
         * Increments the accumulated version by one — used by {@link Model#nextVersion}.
         *
         * @return this builder, for chaining.
         */
        public B increaseVersion() {
            this.version++;
            return self();
        }

        /**
         * Replaces the events list wholesale.
         *
         * @param events the new events list.
         * @return this builder, for chaining.
         */
        public B events(List<ModelEvent<M>> events) {
            this.events = new ArrayList<>(events);
            return self();
        }

        /** {@return a configured model instance; concrete subclasses provide the type} */
        public abstract M build();

        /**
         * Copies an existing model's id, state, timestamps, version, and events onto this
         * builder — used to start a mutation from a fully-loaded model.
         *
         * @param model the model to copy from.
         * @return this builder, for chaining.
         */
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
