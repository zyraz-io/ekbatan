package io.ekbatan.keyedlock.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the lease conversion. The behaviour that matters is what Redisson is handed: a
 * non-positive {@code leaseTime} means "no lease" to Redisson, which activates its renewal watchdog
 * and keeps the key alive for as long as the JVM runs. Any positive {@code maxHold} must therefore
 * produce a positive lease, however small the request.
 */
class RedisKeyedLockProviderLeaseTest {

    @Test
    void sub_millisecond_hold_does_not_truncate_to_zero() {
        // GIVEN a positive hold shorter than a millisecond
        // WHEN converted to Redisson's millisecond lease
        // THEN it rounds up rather than becoming 0, which Redisson would read as "renew forever"
        assertThat(RedisKeyedLockProvider.leaseMillis(Duration.ofNanos(1))).isEqualTo(1L);
        assertThat(RedisKeyedLockProvider.leaseMillis(Duration.ofNanos(500_000)))
                .isEqualTo(1L);
        assertThat(RedisKeyedLockProvider.leaseMillis(Duration.ofNanos(999_999)))
                .isEqualTo(1L);
    }

    @Test
    void whole_millisecond_holds_pass_through_unchanged() {
        assertThat(RedisKeyedLockProvider.leaseMillis(Duration.ofMillis(1))).isEqualTo(1L);
        assertThat(RedisKeyedLockProvider.leaseMillis(Duration.ofMillis(250))).isEqualTo(250L);
        assertThat(RedisKeyedLockProvider.leaseMillis(Duration.ofSeconds(30))).isEqualTo(30_000L);
        assertThat(RedisKeyedLockProvider.leaseMillis(Duration.ofMinutes(5))).isEqualTo(300_000L);
    }

    @Test
    void a_very_long_hold_is_not_clamped() {
        // Guard against a fix that clamps at both ends: only the lower bound is enforced.
        assertThat(RedisKeyedLockProvider.leaseMillis(Duration.ofDays(7))).isEqualTo(604_800_000L);
    }
}
