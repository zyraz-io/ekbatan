package io.ekbatan.core.internal;

import java.util.Collection;
import java.util.Objects;

/**
 * Internal precondition checks used across the framework. Provides the small subset of
 * {@code commons-lang3 Validate} the framework actually uses, so utility code can stay
 * JDK-only. Not part of the public API - do not depend on this class from outside the
 * framework.
 *
 * <p>Exception semantics match {@code commons-lang3 Validate}: a null value throws
 * {@link NullPointerException}, every other failed check throws
 * {@link IllegalArgumentException}.
 *
 * <p>Each method has two overloads - a plain {@code (..., String)} form and a
 * {@code (..., String, Object...)} form that runs the message through
 * {@link String#format(String, Object...)} when the failure path fires. The Java
 * compiler resolves zero-extra-arg calls to the plain form so the common case avoids
 * the varargs array allocation; calls that pass format values pick the varargs form
 * just like {@code commons-lang3 Validate} does.
 */
public final class Validate {

    private Validate() {}

    /**
     * Throws {@link NullPointerException} with the given message if {@code object} is null.
     *
     * @return {@code object} (so the call can appear on the right of an assignment).
     */
    public static <T> T notNull(T object, String message) {
        return Objects.requireNonNull(object, message);
    }

    /** Format-string variant of {@link #notNull(Object, String)}. */
    public static <T> T notNull(T object, String message, Object... values) {
        if (object == null) {
            throw new NullPointerException(formatMessage(message, values));
        }
        return object;
    }

    /** Throws {@link IllegalArgumentException} with the given message if {@code expression} is false. */
    public static void isTrue(boolean expression, String message) {
        if (!expression) {
            throw new IllegalArgumentException(message);
        }
    }

    /** Format-string variant of {@link #isTrue(boolean, String)}. */
    public static void isTrue(boolean expression, String message, Object... values) {
        if (!expression) {
            throw new IllegalArgumentException(formatMessage(message, values));
        }
    }

    /**
     * Throws {@link NullPointerException} if {@code value} is null, or
     * {@link IllegalArgumentException} if it is blank (empty or whitespace-only).
     *
     * @return {@code value} (so the call can appear on the right of an assignment).
     */
    public static String notBlank(String value, String message) {
        if (value == null) {
            throw new NullPointerException(message);
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    /** Format-string variant of {@link #notBlank(String, String)}. */
    public static String notBlank(String value, String message, Object... values) {
        if (value == null) {
            throw new NullPointerException(formatMessage(message, values));
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(formatMessage(message, values));
        }
        return value;
    }

    /**
     * Throws {@link IllegalArgumentException} with the given message if
     * {@code value} falls outside the inclusive range {@code [start, end]}.
     */
    public static void inclusiveBetween(long start, long end, long value, String message) {
        if (value < start || value > end) {
            throw new IllegalArgumentException(message);
        }
    }

    /** Format-string variant of {@link #inclusiveBetween(long, long, long, String)}. */
    public static void inclusiveBetween(long start, long end, long value, String message, Object... values) {
        if (value < start || value > end) {
            throw new IllegalArgumentException(formatMessage(message, values));
        }
    }

    /**
     * Throws {@link NullPointerException} if {@code collection} is null, or
     * {@link IllegalArgumentException} if it is empty.
     *
     * @return {@code collection} (so the call can appear on the right of an assignment).
     */
    public static <T extends Collection<?>> T notEmpty(T collection, String message) {
        if (collection == null) {
            throw new NullPointerException(message);
        }
        if (collection.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return collection;
    }

    /** Format-string variant of {@link #notEmpty(Collection, String)}. */
    public static <T extends Collection<?>> T notEmpty(T collection, String message, Object... values) {
        if (collection == null) {
            throw new NullPointerException(formatMessage(message, values));
        }
        if (collection.isEmpty()) {
            throw new IllegalArgumentException(formatMessage(message, values));
        }
        return collection;
    }

    /**
     * Throws {@link IllegalArgumentException} with the given message if {@code obj}
     * is not an instance of {@code type}.
     */
    public static void isInstanceOf(Class<?> type, Object obj, String message) {
        if (!type.isInstance(obj)) {
            throw new IllegalArgumentException(message);
        }
    }

    /** Format-string variant of {@link #isInstanceOf(Class, Object, String)}. */
    public static void isInstanceOf(Class<?> type, Object obj, String message, Object... values) {
        if (!type.isInstance(obj)) {
            throw new IllegalArgumentException(formatMessage(message, values));
        }
    }

    private static String formatMessage(String message, Object... values) {
        return values == null || values.length == 0 ? message : String.format(message, values);
    }
}
