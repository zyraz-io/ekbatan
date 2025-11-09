package io.ekbatan.core.persistence;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ConnectionHolder<C> {
    private static final ThreadLocal<Map<Object, Object>> resources = ThreadLocal.withInitial(HashMap::new);

    @SuppressWarnings("unchecked")
    public static <C> C getResource(Object key) {
        Object value = doGetResource(key);
        return (C) value;
    }

    public static boolean hasResource(Object key) {
        return doGetResource(key) != null;
    }

    public static <C> void bindResource(Object key, C value) {
        Objects.requireNonNull(key, "Key must not be null");
        Objects.requireNonNull(value, "Value must not be null");

        Map<Object, Object> map = resources.get();
        if (map.put(key, value) != null) {
            throw new IllegalStateException(
                    "Already value [" + map.get(key) + "] for key [" + key + "] bound to thread");
        }
    }

    public static Object unbindResource(Object key) {
        Objects.requireNonNull(key, "Key must not be null");

        Map<Object, Object> map = resources.get();
        Object value = map.remove(key);

        if (map.isEmpty()) {
            resources.remove();
        }

        if (value == null) {
            throw new IllegalStateException("No value for key [" + key + "] bound to thread");
        }

        return value;
    }

    private static Object doGetResource(Object actualKey) {
        Map<Object, Object> map = resources.get();
        if (map == null) {
            return null;
        }
        return map.get(actualKey);
    }

    public static void clear() {
        resources.remove();
    }
}
