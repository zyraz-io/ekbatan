package io.ekbatan.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigTreeBuilderTest {

    @Test
    void shouldRebuildListsFromIntegerKeyedMaps() {
        // GIVEN — Spring's Binder representation of a YAML list
        Map<String, Object> input = orderedMap(Map.of(
                "groups",
                orderedMap(Map.of(
                        "0", orderedMap(Map.of("name", "alpha")),
                        "1", orderedMap(Map.of("name", "beta"))))));

        // WHEN
        var result = ConfigTreeBuilder.normalize(input);

        // THEN
        assertThat(result).isInstanceOf(Map.class);
        var groups = ((Map<?, ?>) result).get("groups");
        assertThat(groups).isInstanceOf(List.class);
        assertThat((List<?>) groups).hasSize(2);
        assertThat(((Map<?, ?>) ((List<?>) groups).get(0)).get("name")).isEqualTo("alpha");
        assertThat(((Map<?, ?>) ((List<?>) groups).get(1)).get("name")).isEqualTo("beta");
    }

    @Test
    void shouldKeepNonNumericKeyedMapsAsMaps() {
        // GIVEN — the configs map with reserved keys must NOT be turned into a list
        Map<String, Object> input = orderedMap(Map.of(
                "configs",
                orderedMap(Map.of(
                        "primaryConfig", orderedMap(Map.of("jdbcUrl", "url1")),
                        "secondaryConfig", orderedMap(Map.of("jdbcUrl", "url2")),
                        "lockConfig", orderedMap(Map.of("jdbcUrl", "url3"))))));

        // WHEN
        var result = ConfigTreeBuilder.normalize(input);

        // THEN
        assertThat(result).isInstanceOf(Map.class);
        var configs = ((Map<?, ?>) result).get("configs");
        assertThat(configs).isInstanceOf(Map.class);
        Map<String, Object> configsMap = castStringMap(configs);
        assertThat(configsMap).containsKeys("primaryConfig", "secondaryConfig", "lockConfig");
    }

    @Test
    void shouldOnlyConvertWhenKeysAreContiguousZeroBasedIntegers() {
        // GIVEN — keys "0" and "2" — gap, so it's not a list
        Map<String, Object> input = orderedMap(Map.of("0", "a", "2", "c"));

        // WHEN
        var result = ConfigTreeBuilder.normalize(input);

        // THEN — stays a map
        assertThat(result).isInstanceOf(Map.class);
    }

    @Test
    void shouldRecurseDeeply() {
        // GIVEN — list within list
        Map<String, Object> input = orderedMap(Map.of(
                "groups",
                orderedMap(Map.of(
                        "0",
                        orderedMap(Map.of(
                                "members",
                                orderedMap(Map.of(
                                        "0", orderedMap(Map.of("id", "a")),
                                        "1", orderedMap(Map.of("id", "b"))))))))));

        // WHEN
        var result = ConfigTreeBuilder.normalize(input);

        // THEN — both layers become lists
        var groups = (List<?>) ((Map<?, ?>) result).get("groups");
        var members = (List<?>) ((Map<?, ?>) groups.get(0)).get("members");
        assertThat(members).hasSize(2);
        assertThat(((Map<?, ?>) members.get(1)).get("id")).isEqualTo("b");
    }

    private static Map<String, Object> orderedMap(Map<String, Object> source) {
        // LinkedHashMap to preserve declared order (Map.of() ordering is unspecified).
        return new LinkedHashMap<>(source);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringMap(Object node) {
        return (Map<String, Object>) node;
    }
}
