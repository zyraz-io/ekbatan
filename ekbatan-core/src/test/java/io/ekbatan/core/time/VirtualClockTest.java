package io.ekbatan.core.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class VirtualClockTest {

    @Test
    void default_clock_returns_real_time() {
        // GIVEN
        final var clock = new VirtualClock();
        final var before = Instant.now();

        // WHEN
        final var result = clock.instant();

        // THEN
        final var after = Instant.now();
        assertThat(result).isBetween(before, after);
    }

    @Test
    void pause_freezes_time() throws InterruptedException {
        // GIVEN
        final var clock = new VirtualClock();
        clock.pause();

        // WHEN
        final var first = clock.instant();
        Thread.sleep(10);
        final var second = clock.instant();

        // THEN
        assertThat(second).isEqualTo(first);
    }

    @Test
    void pause_at_freezes_at_specific_instant() {
        // GIVEN
        final var clock = new VirtualClock();
        final var target = Instant.parse("2025-06-15T12:00:00Z");

        // WHEN
        clock.pauseAt(target);

        // THEN
        assertThat(clock.instant()).isEqualTo(target);

        // AND repeated calls return the same value
        assertThat(clock.instant()).isEqualTo(target);
    }

    @Test
    void advance_moves_frozen_time_forward() {
        // GIVEN
        final var clock = new VirtualClock();
        final var target = Instant.parse("2025-06-15T12:00:00Z");
        clock.pauseAt(target);

        // WHEN
        clock.advance(Duration.ofMinutes(5));

        // THEN
        assertThat(clock.instant()).isEqualTo(Instant.parse("2025-06-15T12:05:00Z"));
    }

    @Test
    void advance_throws_when_not_paused() {
        // GIVEN
        final var clock = new VirtualClock();

        // WHEN / THEN
        assertThatThrownBy(() -> clock.advance(Duration.ofMinutes(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not paused");
    }

    @Test
    void resume_from_ticks_forward_from_given_instant() throws InterruptedException {
        // GIVEN
        final var clock = new VirtualClock();
        final var target = Instant.parse("2000-01-01T00:00:00Z");

        // WHEN
        clock.resumeFrom(target);
        Thread.sleep(50);
        final var result = clock.instant();

        // THEN
        assertThat(result).isAfter(target);

        // AND
        assertThat(Duration.between(target, result).toMillis()).isGreaterThanOrEqualTo(50);
    }

    @Test
    void resume_returns_to_real_time() {
        // GIVEN
        final var clock = new VirtualClock();
        clock.pauseAt(Instant.parse("2000-01-01T00:00:00Z"));

        // WHEN
        clock.resume();

        // THEN
        final var before = Instant.now();
        final var result = clock.instant();
        final var after = Instant.now();
        assertThat(result).isBetween(before, after);
    }

    @Test
    void pause_after_resume_from_freezes_at_offset_time() throws InterruptedException {
        // GIVEN
        final var clock = new VirtualClock();
        final var target = Instant.parse("2000-01-01T00:00:00Z");
        clock.resumeFrom(target);
        Thread.sleep(50);

        // WHEN
        clock.pause();
        final var frozen = clock.instant();
        Thread.sleep(10);

        // THEN
        assertThat(clock.instant()).isEqualTo(frozen);

        // AND
        assertThat(frozen).isAfter(target);
    }
}
