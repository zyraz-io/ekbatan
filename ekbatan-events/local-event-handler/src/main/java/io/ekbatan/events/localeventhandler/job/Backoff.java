package io.ekbatan.events.localeventhandler.job;

import java.time.Duration;

/**
 * Capped exponential backoff used by {@code EventHandlingJob} to schedule retries after a
 * handler invocation throws.
 *
 * <p>The curve doubles from a 30-second base, capped at the supplied {@code cap}:
 * <pre>
 * attempts → 1   2   3   4   5    6    7    …
 * uncapped  30s 1m  2m  4m  8m   16m  32m  …
 * cap=5m    30s 1m  2m  4m  5m   5m   5m   …
 * cap=10m   30s 1m  2m  4m  8m   10m  10m  …
 * </pre>
 *
 * <p>{@code attempts} is the count of attempts <em>so far</em> (i.e. after incrementing on
 * the failure that just happened). The first failure passes {@code attempts = 1} and gets
 * 30 seconds.
 */
public final class Backoff {

    private static final Duration BASE = Duration.ofSeconds(30);

    /** Defensive cap on the exponent; a 7-day cap will EXPIRE the row long before this matters. */
    private static final int MAX_EXPONENT = 30;

    private Backoff() {}

    /**
     * Returns the delay before the next retry given the current attempt count and a cap.
     * Both arguments must be positive.
     */
    public static Duration delay(int attempts, Duration cap) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be >= 1, got " + attempts);
        }
        if (cap == null || cap.isNegative() || cap.isZero()) {
            throw new IllegalArgumentException("cap must be a positive duration, got " + cap);
        }
        final var exp = Math.min(attempts - 1, MAX_EXPONENT);
        final var grown = BASE.multipliedBy(1L << exp);
        return grown.compareTo(cap) > 0 ? cap : grown;
    }
}
