package io.ekbatan.spring;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring's {@code Binder.bind(prefix, Bindable.mapOf(String.class, Object.class))} flattens
 * YAML lists into maps keyed by their numeric index (e.g. {@code groups: { "0": ... }}). Jackson
 * refuses to deserialize that shape into a {@code List<...>}. This helper rebuilds Lists wherever
 * a map's keys are contiguous integer strings starting at zero — leaving mixed/non-numeric maps
 * (like the {@code configs} map keyed by {@code primaryConfig}/{@code lockConfig}) intact.
 */
final class ConfigTreeBuilder {

    private ConfigTreeBuilder() {}

    @SuppressWarnings("unchecked")
    static Object normalize(Object node) {
        if (node instanceof Map<?, ?> map) {
            return normalizeMap((Map<String, Object>) map);
        }
        if (node instanceof List<?> list) {
            return list.stream().map(ConfigTreeBuilder::normalize).toList();
        }
        return node;
    }

    private static Object normalizeMap(Map<String, Object> map) {
        if (looksLikeList(map.keySet())) {
            return map.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Integer::parseInt)))
                    .map(e -> normalize(e.getValue()))
                    .toList();
        }
        var result = new LinkedHashMap<String, Object>(map.size());
        map.forEach((k, v) -> result.put(k, normalize(v)));
        return result;
    }

    private static boolean looksLikeList(java.util.Set<String> keys) {
        if (keys.isEmpty()) return false;
        var seen = new java.util.HashSet<Integer>(keys.size());
        for (var key : keys) {
            int n;
            try {
                n = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                return false;
            }
            if (n < 0 || n >= keys.size() || !seen.add(n)) {
                return false;
            }
        }
        return true;
    }
}
