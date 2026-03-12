package io.ekbatan.core.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class VirtualClockTest {

    @Test
    void default_clock_returns_real_time() {
        final var clock = new VirtualClock();
        final var before = Instant.now();
        final var result = clock.instant();
        final var after = Instant.now();

        assertThat(result).isBetween(before, after);
    }

    @Test
    void pause_freezes_time() throws InterruptedException {
        final var clock = new VirtualClock();
        clock.pause();

        final var first = clock.instant();
        Thread.sleep(10);
        final var second = clock.instant();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void pause_at_freezes_at_specific_instant() {
        final var clock = new VirtualClock();
        final var target = Instant.parse("2025-06-15T12:00:00Z");

        clock.pauseAt(target);

        assertThat(clock.instant()).isEqualTo(target);
        assertThat(clock.instant()).isEqualTo(target);
    }

    @Test
    void advance_moves_frozen_time_forward() {
        final var clock = new VirtualClock();
        final var target = Instant.parse("2025-06-15T12:00:00Z");

        clock.pauseAt(target);
        clock.advance(Duration.ofMinutes(5));

        assertThat(clock.instant()).isEqualTo(Instant.parse("2025-06-15T12:05:00Z"));
    }

    @Test
    void advance_throws_when_not_paused() {
        final var clock = new VirtualClock();

        assertThatThrownBy(() -> clock.advance(Duration.ofMinutes(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not paused");
    }

    @Test
    void resume_from_ticks_forward_from_given_instant() throws InterruptedException {
        final var clock = new VirtualClock();
        final var target = Instant.parse("2000-01-01T00:00:00Z");

        clock.resumeFrom(target);
        Thread.sleep(50);

        final var result = clock.instant();
        assertThat(result).isAfter(target);
        assertThat(Duration.between(target, result).toMillis()).isGreaterThanOrEqualTo(50);
    }

    @Test
    void resume_returns_to_real_time() {
        final var clock = new VirtualClock();
        clock.pauseAt(Instant.parse("2000-01-01T00:00:00Z"));

        clock.resume();

        final var before = Instant.now();
        final var result = clock.instant();
        final var after = Instant.now();

        assertThat(result).isBetween(before, after);
    }

    @Test
    void pause_after_resume_from_freezes_at_offset_time() throws InterruptedException {
        final var clock = new VirtualClock();
        final var target = Instant.parse("2000-01-01T00:00:00Z");

        clock.resumeFrom(target);
        Thread.sleep(50);
        clock.pause();

        final var frozen = clock.instant();
        Thread.sleep(10);

        assertThat(clock.instant()).isEqualTo(frozen);
        assertThat(frozen).isAfter(target);
    }
}
