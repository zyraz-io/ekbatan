package io.ekbatan.core.action;

import java.time.Duration;
import org.apache.commons.lang3.Validate;

public final class RetryConfig {

    public final int maxAttempts;
    public final Duration delay;

    public RetryConfig(int maxAttempts, Duration delay) {
        Validate.isTrue(maxAttempts >= 0, "maxAttempts must be non-negative");
        this.maxAttempts = maxAttempts;
        this.delay = Validate.notNull(delay, "delay cannot be null");
    }
}
