package io.ekbatan.core.domain;

/**
 * Marker interface for any type that exposes a typed identifier. The base contract that
 * {@link Persistable} (and therefore {@link Model} / {@link Entity}) extend.
 *
 * <p>Used by the framework as a generic constraint where ID-typing matters but the rest of
 * the persistence contract doesn't - e.g. {@link Id} carries an {@code Identifiable} class
 * argument so that {@code Id<Wallet>} and {@code Id<Account>} are statically incompatible.
 *
 * @param <ID> the identifier type for this domain object.
 */
public interface Identifiable<ID> {
    /** {@return the primary identifier for this object} */
    ID getId();
}
