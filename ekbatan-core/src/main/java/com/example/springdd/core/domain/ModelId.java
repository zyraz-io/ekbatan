package com.example.springdd.core.domain;

/**
 * Base interface for all model IDs in the system.
 *
 * @param <T> The type of the ID value, must be comparable.
 */
public interface ModelId<T extends Comparable<T>> {

    /**
     * Returns the ID value.
     */
    T getId();

    /**
     * Returns the string representation of the ID.
     * Default implementation returns the string representation of the ID.
     */
    default String stringValue() {
        return String.valueOf(getId());
    }
}
