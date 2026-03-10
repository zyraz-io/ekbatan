package io.ekbatan.core.action;

import io.ekbatan.core.repository.exception.StaleRecordException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ExecutionConfiguration {

    public final Map<Class<? extends Exception>, RetryConfig> retryConfigs;

    private ExecutionConfiguration(Builder builder) {
        this.retryConfigs = Map.copyOf(builder.retryConfigs);
    }

    public static final class Builder {
        private final Map<Class<? extends Exception>, RetryConfig> retryConfigs =
                new LinkedHashMap<>(Map.of(StaleRecordException.class, new RetryConfig(1, Duration.ofMillis(100))));

        private Builder() {}

        public static Builder executionConfiguration() {
            return new Builder();
        }

        public Builder withRetry(Class<? extends Exception> exceptionType, RetryConfig config) {
            this.retryConfigs.put(exceptionType, config);
            return this;
        }

        public Builder retryConfigs(Map<Class<? extends Exception>, RetryConfig> retryConfigs) {
            this.retryConfigs.clear();
            this.retryConfigs.putAll(retryConfigs);
            return this;
        }

        public Builder noRetry() {
            this.retryConfigs.clear();
            return this;
        }

        public ExecutionConfiguration build() {
            return new ExecutionConfiguration(this);
        }
    }
}
