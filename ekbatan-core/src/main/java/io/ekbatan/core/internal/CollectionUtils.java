package io.ekbatan.core.internal;

import java.util.Collection;

/**
 * Internal null-safe emptiness checks for {@link Collection}. Mirrors the small subset
 * of {@code commons-collections4 CollectionUtils} the framework uses, so utility code
 * can stay JDK-only. Not part of the public API.
 */
public final class CollectionUtils {

    private CollectionUtils() {}

    /** {@return {@code true} if {@code collection} is {@code null} or contains no elements} */
    public static boolean isEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }
}
