package io.ekbatan.core.action;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import java.util.Map;

final class Retry<R> {

    public final Map<Class<? extends Exception>, RetryConfig> retryConfigs;

    private Retry(Map<Class<? extends Exception>, RetryConfig> retryConfigs) {
        this.retryConfigs = retryConfigs;
    }

    public static <R> Retry<R> with(Map<Class<? extends Exception>, RetryConfig> retryConfigs) {
        return new Retry<>(retryConfigs);
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
