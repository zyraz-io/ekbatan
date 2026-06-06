package io.ekbatan.core.action;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Retry<R> {

    private static final Logger LOG = LoggerFactory.getLogger(Retry.class);

    public final Map<Class<? extends Exception>, RetryConfig> retryConfigs;
    private final String actionName;

    private Retry(Map<Class<? extends Exception>, RetryConfig> retryConfigs, String actionName) {
        this.retryConfigs = retryConfigs;
        this.actionName = actionName;
    }

    public static <R> Retry<R> with(Map<Class<? extends Exception>, RetryConfig> retryConfigs, String actionName) {
        return new Retry<>(retryConfigs, actionName);
    }

    public R execute(CheckedSupplier<R> operation) throws Exception {
        int currentRetryCount = 0;

        do {
            Span.current().setAttribute("ekbatan.action.retry.count", currentRetryCount);
            try {
                return operation.get();
            } catch (Exception e) {
                var retryMatch = retryConfigFor(e);
                if (retryMatch == null || currentRetryCount >= retryMatch.config.maxRetries) {
                    throw e;
                }
                currentRetryCount++;
                LOG.warn(
                        "Retrying {} (retry {}/{}) due to {}: {}",
                        actionName,
                        currentRetryCount,
                        retryMatch.config.maxRetries,
                        retryMatch.exception.getClass().getSimpleName(),
                        retryMatch.exception.getMessage());
                Span.current()
                        .addEvent(
                                "retry",
                                Attributes.builder()
                                        .put("retry.count", currentRetryCount)
                                        .put(
                                                "retry.exception",
                                                retryMatch.exception.getClass().getSimpleName())
                                        .build());
                sleepBeforeRetry(retryMatch.config.delay);
            }
        } while (true);
    }

    private void sleepBeforeRetry(java.time.Duration delay) throws InterruptedException {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private RetryMatch retryConfigFor(Exception exception) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable current = exception;
        while (current != null && seen.add(current)) {
            var config = retryConfigs.get(current.getClass());
            if (config != null) {
                return new RetryMatch(config, current);
            }
            current = current.getCause();
        }
        return null;
    }

    private record RetryMatch(RetryConfig config, Throwable exception) {}

    @FunctionalInterface
    interface CheckedSupplier<R> {
        R get() throws Exception;
    }
}
