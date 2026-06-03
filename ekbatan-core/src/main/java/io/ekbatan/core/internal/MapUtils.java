package io.ekbatan.core.internal;

import java.util.Map;

/**
 * Internal null-safe emptiness checks for {@link Map}. Mirrors the small subset of
 * {@code commons-collections4 MapUtils} the framework uses, so utility code can stay
 * JDK-only. Not part of the public API.
 */
public final class MapUtils {

    private MapUtils() {}

    /** {@return {@code true} if {@code map} is non-null and contains at least one entry} */
    public static boolean isNotEmpty(Map<?, ?> map) {
        return map != null && !map.isEmpty();
    }
}
