package io.ekbatan.core.action;

import io.ekbatan.core.repository.exception.StaleRecordException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-call (or default per-executor) knobs for {@link ActionExecutor}: the retry policy
 * keyed by exact exception type, and whether the action is allowed to touch more than one shard.
 *
 * <p>The default configuration is conservative: retry {@link StaleRecordException} once
 * after 100ms (absorbs a transient optimistic-lock conflict) and reject any action that
 * touches more than one shard. Override either via the {@link Builder} when constructing
 * the executor, or pass an override on a single call via
 * {@link ActionExecutor#execute(java.security.Principal, Class, Object, ExecutionConfiguration)}.
 *
 * <h2>Cross-shard semantics</h2>
 *
 * <p>{@link #allowCrossShard} = {@code true} lets the executor commit per-shard transactions
 * independently. This is NOT two-phase commit - if a later shard fails, earlier shards stay
 * committed. Use only when the action is genuinely idempotent on retry and the cross-shard
 * change is acceptable as an at-least-once side-effect.
 */
public final class ExecutionConfiguration {

    /**
     * Immutable map of retry policies, keyed by exact exception class. The executor checks the
     * thrown exception and its cause chain; superclass matching is not used.
     */
    public final Map<Class<? extends Exception>, RetryConfig> retryConfigs;

    /** Whether the action is allowed to touch more than one shard (no atomic cross-shard commit - see class docs). */
    public final boolean allowCrossShard;

    private ExecutionConfiguration(Builder builder) {
        this.retryConfigs = Map.copyOf(builder.retryConfigs);
        this.allowCrossShard = builder.allowCrossShard;
    }

    /** Fluent builder for {@link ExecutionConfiguration}. Obtain via {@link #executionConfiguration()}. */
    public static final class Builder {
        private final Map<Class<? extends Exception>, RetryConfig> retryConfigs =
                new LinkedHashMap<>(Map.of(StaleRecordException.class, new RetryConfig(1, Duration.ofMillis(100))));
        private boolean allowCrossShard = false;

        private Builder() {}

        /** {@return a fresh builder for {@link ExecutionConfiguration}} */
        public static Builder executionConfiguration() {
            return new Builder();
        }

        /**
         * Adds (or replaces) a retry policy for the given exact exception type. The executor
         * matches this class against the thrown exception and each cause in its cause chain;
         * superclass matching is not used.
         *
         * @param exceptionType the exact exception class to match.
         * @param config the retry configuration to apply when this exception is thrown or found in the cause chain.
         * @return this builder, for chaining.
         */
        public Builder withRetry(Class<? extends Exception> exceptionType, RetryConfig config) {
            this.retryConfigs.put(exceptionType, config);
            return this;
        }

        /**
         * Replaces the entire retry policy map with the given one.
         *
         * @param retryConfigs the new retry-policy map.
         * @return this builder, for chaining.
         */
        public Builder retryConfigs(Map<Class<? extends Exception>, RetryConfig> retryConfigs) {
            this.retryConfigs.clear();
            this.retryConfigs.putAll(retryConfigs);
            return this;
        }

        /**
         * Disables all retries, including the default {@link StaleRecordException} retry.
         *
         * @return this builder, for chaining.
         */
        public Builder noRetry() {
            this.retryConfigs.clear();
            return this;
        }

        /**
         * Sets the cross-shard policy.
         *
         * @param allowCrossShard {@code true} to allow per-shard transactions on multiple shards
         *     within one action invocation (no atomic cross-shard commit - see class docs).
         * @return this builder, for chaining.
         */
        public Builder allowCrossShard(boolean allowCrossShard) {
            this.allowCrossShard = allowCrossShard;
            return this;
        }

        /** {@return a configured {@link ExecutionConfiguration}} */
        public ExecutionConfiguration build() {
            return new ExecutionConfiguration(this);
        }
    }
}
