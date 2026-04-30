package io.ekbatan.quarkus.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.Config;

/**
 * Reconstructs a nested map/list tree from a flat SmallRye {@link Config} subtree under a given
 * prefix. SmallRye exposes hierarchical YAML as flat keys with index notation
 * ({@code groups[0].members[0].configs.primaryConfig.jdbcUrl}); Jackson needs the nested form.
 *
 * <p>Keys are stored verbatim — Ekbatan's sharding YAML uses camelCase ({@code jdbcUrl},
 * {@code primaryConfig}) to match the Jackson Builder method names resolved via
 * {@code @JsonPOJOBuilder(withPrefix = "")}.
 */
final class ConfigTreeBuilder {

    private ConfigTreeBuilder() {}

    static Map<String, Object> readSubtree(Config config, String rootPrefix) {
        var prefix = rootPrefix.endsWith(".") ? rootPrefix : rootPrefix + ".";
        var tree = new LinkedHashMap<String, Object>();
        for (var name : config.getPropertyNames()) {
            if (!name.startsWith(prefix)) continue;
            var sub = name.substring(prefix.length());
            if (sub.isEmpty()) continue;
            var value = config.getOptionalValue(name, String.class).orElse(null);
            if (value == null) continue;
            insert(tree, parseTokens(sub), value);
        }
        return tree;
    }

    @SuppressWarnings("unchecked")
    private static void insert(Map<String, Object> root, List<Token> tokens, String value) {
        Object cursor = root;
        for (int i = 0; i < tokens.size(); i++) {
            var tok = tokens.get(i);
            var isLast = i == tokens.size() - 1;
            final int nextIdx = i + 1;
            Supplier<Object> mkChild =
                    () -> tokens.get(nextIdx) instanceof IndexToken ? new ArrayList<>() : new LinkedHashMap<>();
            if (tok instanceof KeyToken k) {
                var map = (Map<String, Object>) cursor;
                if (isLast) {
                    map.put(k.name(), value);
                    return;
                }
                cursor = map.computeIfAbsent(k.name(), x -> mkChild.get());
            } else {
                var idx = ((IndexToken) tok).index();
                var list = (List<Object>) cursor;
                while (list.size() <= idx) list.add(null);
                if (isLast) {
                    list.set(idx, value);
                    return;
                }
                var existing = list.get(idx);
                if (existing == null) {
                    existing = mkChild.get();
                    list.set(idx, existing);
                }
                cursor = existing;
            }
        }
    }

    private static List<Token> parseTokens(String path) {
        var out = new ArrayList<Token>();
        for (var seg : path.split("\\.")) {
            int cursor = 0;
            int len = seg.length();
            while (cursor < len) {
                if (seg.charAt(cursor) == '[') {
                    int end = seg.indexOf(']', cursor);
                    if (end < 0) {
                        throw new IllegalStateException("Malformed property segment '" + seg + "' — unmatched [");
                    }
                    out.add(new IndexToken(Integer.parseInt(seg.substring(cursor + 1, end))));
                    cursor = end + 1;
                } else {
                    int next = seg.indexOf('[', cursor);
                    if (next < 0) {
                        out.add(new KeyToken(seg.substring(cursor)));
                        cursor = len;
                    } else {
                        out.add(new KeyToken(seg.substring(cursor, next)));
                        cursor = next;
                    }
                }
            }
        }
        return out;
    }

    private sealed interface Token permits KeyToken, IndexToken {}

    private record KeyToken(String name) implements Token {}

    private record IndexToken(int index) implements Token {}
}
