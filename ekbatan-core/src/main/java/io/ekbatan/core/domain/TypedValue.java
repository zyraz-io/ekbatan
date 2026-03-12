package io.ekbatan.core.domain;

import org.apache.commons.lang3.Validate;

/**
 * Base class for typed value wrappers that give a raw value a domain-specific type.
 * Provides type safety and value semantics.
 *
 * @param <T> The type of the wrapped value
 */
public abstract class TypedValue<T> {
    private final T value;

    /**
     * Creates a new typed value wrapper.
     *
     * @param value The value to wrap (must not be null)
     * @throws IllegalArgumentException if value is null
     */
    protected TypedValue(T value) {
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
        TypedValue<?> microType = (TypedValue<?>) o;
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
