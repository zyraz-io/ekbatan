package io.ekbatan.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CollectionUtilsTest {

    @Test
    void isEmpty_returns_true_for_null() {
        assertThat(CollectionUtils.isEmpty(null)).isTrue();
    }

    @Test
    void isEmpty_returns_true_for_empty_collection() {
        assertThat(CollectionUtils.isEmpty(List.of())).isTrue();
        assertThat(CollectionUtils.isEmpty(Set.of())).isTrue();
    }

    @Test
    void isEmpty_returns_false_for_populated_collection() {
        assertThat(CollectionUtils.isEmpty(List.of("a"))).isFalse();
        assertThat(CollectionUtils.isEmpty(Set.of(1, 2, 3))).isFalse();
    }
}
