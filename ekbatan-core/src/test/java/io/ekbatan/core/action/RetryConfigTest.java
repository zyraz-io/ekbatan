package io.ekbatan.core.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryConfigTest {

    @Test
    void constructor_sets_fields() {
        // WHEN
        var config = new RetryConfig(3, Duration.ofMillis(200));

        // THEN
        assertThat(config.maxAttempts).isEqualTo(3);

        // AND
        assertThat(config.delay).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void constructor_allows_zero_attempts() {
        // WHEN
        var config = new RetryConfig(0, Duration.ZERO);

        // THEN
        assertThat(config.maxAttempts).isZero();
    }

    @Test
    void constructor_rejects_negative_attempts() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> new RetryConfig(-1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts must be non-negative");
    }

    @Test
    void constructor_rejects_null_delay() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> new RetryConfig(1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("delay cannot be null");
    }
}
