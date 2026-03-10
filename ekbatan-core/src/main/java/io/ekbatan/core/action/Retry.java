package io.ekbatan.core.action;

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
        int attempt = 0;

        do {
            try {
                return operation.get();
            } catch (Exception e) {
                var config = retryConfigs.get(e.getClass());
                if (config == null || attempt >= config.maxAttempts) {
                    throw e;
                }
                Thread.sleep(config.delay.toMillis());
                attempt++;
            }
        } while (true);
    }

    @FunctionalInterface
    interface CheckedSupplier<R> {
        R get() throws Exception;
    }
}
