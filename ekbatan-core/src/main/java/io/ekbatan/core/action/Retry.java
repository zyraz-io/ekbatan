package io.ekbatan.core.action;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
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
                var config = retryConfigs.get(e.getClass());
                if (config == null || currentRetryCount >= config.maxRetries) {
                    throw e;
                }
                currentRetryCount++;
                LOG.warn(
                        "Retrying {} (retry {}/{}) due to {}: {}",
                        actionName,
                        currentRetryCount,
                        config.maxRetries,
                        e.getClass().getSimpleName(),
                        e.getMessage());
                Span.current()
                        .addEvent(
                                "retry",
                                Attributes.builder()
                                        .put("retry.count", currentRetryCount)
                                        .put("retry.exception", e.getClass().getSimpleName())
                                        .build());
                Thread.sleep(config.delay.toMillis());
            }
        } while (true);
    }

    @FunctionalInterface
    interface CheckedSupplier<R> {
        R get() throws Exception;
    }
}
