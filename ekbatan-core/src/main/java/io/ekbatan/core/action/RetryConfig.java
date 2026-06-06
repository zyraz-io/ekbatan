package io.ekbatan.core.action;

import io.ekbatan.core.internal.Validate;
import java.time.Duration;

/**
 * Retry policy for a single exact exception type within an {@link ExecutionConfiguration}: how
 * many times to retry and how long to wait between attempts. The executor also checks causes
 * for exact matches to configured exception classes; superclass matching is not used.
 *
 * <p>{@code maxRetries = 0} disables retrying (the exception propagates on first throw);
 * larger values give the executor up to N retries (so up to N+1 total attempts). Each retry
 * builds a fresh {@code ActionPlan} so attempts are logically independent.
 */
public final class RetryConfig {

    /** Maximum number of retries (so up to {@code maxRetries + 1} total attempts). Zero disables retrying. */
    public final int maxRetries;

    /** Delay between retry attempts. */
    public final Duration delay;

    /**
     * Constructs the policy.
     *
     * @param maxRetries non-negative retry count.
     * @param delay non-null delay between attempts.
     */
    public RetryConfig(int maxRetries, Duration delay) {
        Validate.isTrue(maxRetries >= 0, "maxRetries must be non-negative");
        this.maxRetries = maxRetries;
        this.delay = Validate.notNull(delay, "delay cannot be null");
    }
}
