package io.ekbatan.core.domain;

import io.ekbatan.core.internal.Validate;

/**
 * A persistent aggregate that does NOT emit events. Use {@code Entity} when the persistence
 * layer needs optimistic-locked rows whose changes don't carry domain meaning worth recording
 * - caches, lookup tables, idempotency markers, audit-only side-effects.
 *
 * <p>The framework's other persistent shape is {@link Model}, which DOES emit events.
 * {@link io.ekbatan.core.action.Action} code stages additions / updates of either kind on the
 * implicit {@code ActionPlan}; {@code ActionExecutor} writes the domain row and (only for
 * {@code Model}) extracts events into the eventlog atomically.
 *
 * <p>Subclasses are immutable with a generated {@code *Builder} via {@code @AutoBuilder} from
 * {@code ekbatan-annotation-processor}. Mutations produce a new instance via {@code copy()...
 * build()}; the builder threads the version forward so {@link #nextVersion()} returns the
 * properly-versioned successor for the repository's optimistic-locked update.
 *
 * <h2>Equality</h2>
 *
 * <p>Two {@code Entity} instances are equal iff their {@code id}, {@code state}, and
 * {@code version} match - there's no event list and no timestamps to consider, so the equality
 * surface is narrower than {@link Model}'s.
 *
 * @param <ENTITY> the concrete subclass (CRTP-style self-type)
 * @param <ID>     the identifier type (usually {@link Id} or {@link io.ekbatan.core.domain.ShardedId})
 * @param <STATE>  the state-enum type for this entity's lifecycle (see {@link GenericState} as a default)
 */
public abstract class Entity<
                ENTITY extends Entity<ENTITY, ID, STATE>, ID extends Comparable<?>, STATE extends Enum<STATE>>
        implements Persistable<ID> {

    /** The entity's primary identifier. */
    public final ID id;

    /** The entity's lifecycle state. */
    public final STATE state;

    /** Optimistic-locking version; incremented on each update. */
    public final Long version;

    /**
     * Subclass constructor invoked by the generated builder's {@code build()}.
     *
     * @param builder the builder carrying validated field values.
     * @param <B> the concrete builder type (CRTP).
     */
    protected <B extends Builder<ID, B, ENTITY, STATE>> Entity(Builder<ID, B, ENTITY, STATE> builder) {
        this.id = Validate.notNull(builder.id, "id cannot be null");
        this.state = Validate.notNull(builder.state, "state cannot be null");
        this.version = Validate.notNull(builder.version, "version cannot be null");
        Validate.isTrue(builder.version >= 1, "version must be >= 1");
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
        return false;
    }

    /** {@return a new builder pre-populated with this entity's field values} */
    public abstract Entity.Builder<ID, ?, ENTITY, STATE> copy();

    @Override
    @SuppressWarnings("unchecked")
    public <E extends Persistable<ID>> E nextVersion() {
        return (E) copy().increaseVersion().build();
    }

    /**
     * Base fluent builder for {@link Entity} subclasses. CRTP-typed self-type so concrete
     * builders can add domain fields and still chain through the base setters.
     *
     * @param <ID> the entity's identifier type.
     * @param <B> the concrete builder type (CRTP self-type).
     * @param <E> the concrete entity type.
     * @param <STATE> the entity's state enum type.
     */
    public abstract static class Builder<
            ID extends Comparable<?>,
            B extends Builder<ID, B, E, STATE>,
            E extends Entity<E, ID, STATE>,
            STATE extends Enum<STATE>> {

        /** Builder's accumulated id field. */
        protected ID id;

        /** Builder's accumulated state field. */
        protected STATE state;

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
         * Sets the entity's primary identifier.
         *
         * @param id the entity's id.
         * @return this builder, for chaining.
         */
        public B id(ID id) {
            this.id = id;
            return self();
        }

        /**
         * Sets the entity's lifecycle state.
         *
         * @param state the state value.
         * @return this builder, for chaining.
         */
        public B state(STATE state) {
            this.state = state;
            return self();
        }

        /**
         * Initializes the version to 1 - used by callers staging a new entity for first insert.
         *
         * @return this builder, for chaining.
         */
        public B withInitialVersion() {
            this.version = 1L;
            return self();
        }

        /**
         * Sets the entity's optimistic-locking version explicitly.
         *
         * @param version the version value (must be >= 1 at build time).
         * @return this builder, for chaining.
         */
        public B version(Long version) {
            this.version = version;
            return self();
        }

        /**
         * Increments the accumulated version by one - used by {@link Entity#nextVersion}.
         *
         * @return this builder, for chaining.
         */
        public B increaseVersion() {
            this.version++;
            return self();
        }

        /** {@return a configured entity instance; concrete subclasses provide the type} */
        public abstract E build();

        /**
         * Copies an existing entity's id, state, and version onto this builder - used to start
         * a mutation from a fully-loaded entity.
         *
         * @param entity the entity to copy from.
         * @return this builder, for chaining.
         */
        public B copyBase(E entity) {
            return self().id(entity.id).state(entity.state).version(entity.version);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;
        Entity<?, ?, ?> entity = (Entity<?, ?, ?>) other;
        return id.equals(entity.id) && state.equals(entity.state) && version.equals(entity.version);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
