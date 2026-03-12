package io.ekbatan.core.action;

import static io.ekbatan.core.action.ExecutionConfiguration.Builder.executionConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.repository.exception.StaleRecordException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionConfigurationTest {

    @Test
    void default_config_has_stale_record_retry() {
        // WHEN
        var config = executionConfiguration().build();

        // THEN
        assertThat(config.retryConfigs).containsKey(StaleRecordException.class);
        var retryConfig = config.retryConfigs.get(StaleRecordException.class);
        assertThat(retryConfig.maxAttempts).isEqualTo(1);
        assertThat(retryConfig.delay).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void withRetry_adds_custom_exception_retry() {
        // WHEN
        var config = executionConfiguration()
                .withRetry(IllegalStateException.class, new RetryConfig(3, Duration.ofSeconds(1)))
                .build();

        // THEN
        assertThat(config.retryConfigs).hasSize(2);
        assertThat(config.retryConfigs).containsKey(StaleRecordException.class);
        assertThat(config.retryConfigs).containsKey(IllegalStateException.class);
        assertThat(config.retryConfigs.get(IllegalStateException.class).maxAttempts)
                .isEqualTo(3);
    }

    @Test
    void withRetry_overrides_existing_exception_config() {
        // WHEN
        var config = executionConfiguration()
                .withRetry(StaleRecordException.class, new RetryConfig(5, Duration.ofSeconds(2)))
                .build();

        // THEN
        assertThat(config.retryConfigs).hasSize(1);
        assertThat(config.retryConfigs.get(StaleRecordException.class).maxAttempts)
                .isEqualTo(5);
    }

    @Test
    void retryConfigs_replaces_all_configs() {
        // GIVEN
        var customConfigs = Map.<Class<? extends Exception>, RetryConfig>of(
                IllegalArgumentException.class, new RetryConfig(2, Duration.ZERO));

        // WHEN
        var config = executionConfiguration().retryConfigs(customConfigs).build();

        // THEN
        assertThat(config.retryConfigs).hasSize(1);
        assertThat(config.retryConfigs).containsKey(IllegalArgumentException.class);
        assertThat(config.retryConfigs).doesNotContainKey(StaleRecordException.class);
    }

    @Test
    void noRetry_clears_all_configs() {
        // WHEN
        var config = executionConfiguration().noRetry().build();

        // THEN
        assertThat(config.retryConfigs).isEmpty();
    }

    @Test
    void retryConfigs_map_is_immutable() {
        // GIVEN
        var config = executionConfiguration().build();

        // WHEN / THEN
        assertThat(config.retryConfigs).isUnmodifiable();
    }
}
