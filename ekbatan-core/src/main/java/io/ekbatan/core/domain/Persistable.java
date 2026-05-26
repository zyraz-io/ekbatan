package io.ekbatan.core.domain;

/**
 * Anything the persistence layer can write or update. Adds optimistic-locking version, a
 * {@link #nextVersion()} hook, and an {@link #isModel()} discriminator on top of
 * {@link Identifiable}. {@link Model} and {@link Entity} are the two concrete shapes.
 *
 * <p>Repository operations are versioned: every {@code UPDATE} carries a {@code WHERE version=?}
 * clause derived from {@link #getVersion()} and increments to the value supplied by
 * {@link #nextVersion()}. A zero-row update therefore raises
 * {@link io.ekbatan.core.repository.exception.StaleRecordException} rather than silently
 * losing data - the canonical optimistic-concurrency contract.
 *
 * <p>{@link #isModel()} returns {@code true} for {@link Model} (event-emitting) and
 * {@code false} for {@link Entity} (no events). The persister branches on this when deciding
 * whether to extract events from {@code Model.events} into the eventlog.
 *
 * @param <ID> the identifier type for this domain object; must be {@link Comparable} for stable iteration order in batched operations.
 */
public interface Persistable<ID extends Comparable<?>> extends Identifiable<ID> {
    /** {@return {@code true} for {@link Model} (event-emitting), {@code false} for {@link Entity} (no events)} */
    boolean isModel();

    /** {@return the current optimistic-locking version (incremented per write)} */
    Long getVersion();

    /**
     * Returns a copy of this object with the optimistic-locking version incremented; used by
     * repositories before issuing an {@code UPDATE}.
     *
     * @param <E> the concrete persistable subtype; the framework reflectively returns the same runtime type as {@code this}.
     * @return a copy of this object with {@code version + 1}.
     */
    <E extends Persistable<ID>> E nextVersion();
}
