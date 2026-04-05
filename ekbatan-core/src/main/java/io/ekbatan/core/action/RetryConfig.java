package io.ekbatan.core.action;

import java.time.Duration;
import org.apache.commons.lang3.Validate;

public final class RetryConfig {

    public final int maxRetries;
    public final Duration delay;

    public RetryConfig(int maxRetries, Duration delay) {
        Validate.isTrue(maxRetries >= 0, "maxRetries must be non-negative");
        this.maxRetries = maxRetries;
        this.delay = Validate.notNull(delay, "delay cannot be null");
    }
}
