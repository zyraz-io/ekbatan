package io.ekbatan.core.domain;

import org.apache.commons.lang3.Validate;

public abstract class Entity<
                ENTITY extends Entity<ENTITY, ID, STATE>, ID extends Comparable<?>, STATE extends Enum<STATE>>
        implements Persistable<ID> {
    public final ID id;
    public final STATE state;
    public final Long version;

    protected <B extends Builder<ID, B, ENTITY, STATE>> Entity(Builder<ID, B, ENTITY, STATE> builder) {
        this.id = Validate.notNull(builder.id, "id cannot be null");
        this.state = Validate.notNull(builder.state, "state cannot be null");
        this.version = Validate.notNull(builder.version, "version cannot be null");
        Validate.isTrue(builder.version >= 1, "version must be ≥ 1");
    }

    @Override
    public ID getId() {
        return id;
    }

    @Override
    public final boolean isModel() {
        return false;
    }

    public abstract Entity.Builder<ID, ?, ENTITY, STATE> copy();

    @Override
    @SuppressWarnings("unchecked")
    public <E extends Persistable<ID>> E nextVersion() {
        return (E) copy().increaseVersion().build();
    }

    public abstract static class Builder<
            ID extends Comparable<?>,
            B extends Builder<ID, B, E, STATE>,
            E extends Entity<E, ID, STATE>,
            STATE extends Enum<STATE>> {

        protected ID id;
        protected STATE state;
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

        public B state(STATE state) {
            this.state = state;
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

        public abstract E build();

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
