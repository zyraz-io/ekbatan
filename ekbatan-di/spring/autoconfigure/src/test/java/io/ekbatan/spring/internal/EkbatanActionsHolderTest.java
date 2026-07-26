package io.ekbatan.spring.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.spring.fixture.FixtureAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EkbatanActionsHolderTest {

    @AfterEach
    void drain() {
        EkbatanActionsHolder.consume();
    }

    @Test
    void should_hand_over_the_classes_that_were_set() {
        EkbatanActionsHolder.set(FixtureAction.class);

        assertThat(EkbatanActionsHolder.consume()).containsExactly(FixtureAction.class);
    }

    @Test
    void should_be_empty_for_the_next_reader() {
        // The defect: the holder is one static slot per JVM, so a second application context in
        // the same JVM found the first context's actions still sitting there, treated them as its
        // own AOT list, and skipped the classpath scan that would have found the right ones.
        EkbatanActionsHolder.set(FixtureAction.class);
        EkbatanActionsHolder.consume();

        assertThat(EkbatanActionsHolder.consume()).isEmpty();
    }

    @Test
    void should_be_empty_when_nothing_was_ever_set() {
        assertThat(EkbatanActionsHolder.consume()).isEmpty();
    }
}
