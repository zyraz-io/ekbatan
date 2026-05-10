package io.ekbatan.core.domain;

/**
 * Base interface for all model IDs in the system.
 *
 * @param <T> The type of the ID value, must be comparable.
 */
public interface ModelId<T extends Comparable<T>> {

    /**
     * Returns the ID value.
     *
     * @return the wrapped identifier value.
     */
    T getId();

    /**
     * Returns the string representation of the ID. Default implementation returns
     * {@code String.valueOf(getId())}.
     *
     * @return the string form of the wrapped value.
     */
    default String stringValue() {
        return String.valueOf(getId());
    }
}
