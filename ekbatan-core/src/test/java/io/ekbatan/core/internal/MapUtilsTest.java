package io.ekbatan.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MapUtilsTest {

    @Test
    void isNotEmpty_returns_false_for_null() {
        assertThat(MapUtils.isNotEmpty(null)).isFalse();
    }

    @Test
    void isNotEmpty_returns_false_for_empty_map() {
        assertThat(MapUtils.isNotEmpty(Map.of())).isFalse();
    }

    @Test
    void isNotEmpty_returns_true_for_populated_map() {
        assertThat(MapUtils.isNotEmpty(Map.of("k", "v"))).isTrue();
    }
}
