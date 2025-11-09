package io.ekbatan.core.domain;

import org.apache.commons.lang3.Validate;

/**
 * Base class for micro types that wrap a single value.
 * Provides type safety and value semantics.
 *
 * @param <T> The type of the wrapped value
 */
public abstract class MicroType<T> {
    private final T value;

    /**
     * Creates a new MicroType with the given value.
     *
     * @param value The value to wrap (must not be null)
     * @throws IllegalArgumentException if value is null
     */
    protected MicroType(T value) {
        this.value = Validate.notNull(value, "value cannot be null");
    }

    /**
     * Returns the wrapped value.
     *
     * @return the wrapped value, never null
     */
    public T getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MicroType<?> microType = (MicroType<?>) o;
        return value.equals(microType.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" + value + '}';
    }
}
