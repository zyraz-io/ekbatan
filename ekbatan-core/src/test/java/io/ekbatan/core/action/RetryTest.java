package io.ekbatan.core.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryTest {

    @Test
    void execute_returns_result_on_success() throws Exception {
        // GIVEN
        var retry = Retry.with(Map.of(), "TestAction");

        // WHEN
        var result = retry.execute(() -> "ok");

        // THEN
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void execute_throws_when_no_retry_configured() {
        // GIVEN
        var retry = Retry.with(Map.of(), "TestAction");

        // WHEN / THEN
        assertThatThrownBy(() -> retry.execute(() -> {
                    throw new IllegalStateException("fail");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("fail");
    }

    @Test
    void execute_retries_on_configured_exception() throws Exception {
        // GIVEN
        var config = new RetryConfig(2, Duration.ZERO);
        var retry = Retry.with(Map.of(IllegalStateException.class, config), "TestAction");
        var attempts = new AtomicInteger(0);

        // WHEN
        var result = retry.execute(() -> {
            if (attempts.incrementAndGet() <= 1) {
                throw new IllegalStateException("transient");
            }
            return "recovered";
        });

        // THEN
        assertThat(result).isEqualTo("recovered");

        // AND
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void execute_retries_when_configured_exception_is_wrapped_as_cause() throws Exception {
        // GIVEN
        var config = new RetryConfig(2, Duration.ZERO);
        var retry = Retry.with(Map.of(IllegalStateException.class, config), "TestAction");
        var attempts = new AtomicInteger(0);

        // WHEN
        var result = retry.execute(() -> {
            if (attempts.incrementAndGet() <= 1) {
                throw new RuntimeException("wrapped", new IllegalStateException("transient"));
            }
            return "recovered";
        });

        // THEN
        assertThat(result).isEqualTo("recovered");

        // AND
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void execute_retries_when_configured_exception_is_nested_in_cause_chain() throws Exception {
        // GIVEN
        var config = new RetryConfig(2, Duration.ZERO);
        var retry = Retry.with(Map.of(IllegalStateException.class, config), "TestAction");
        var attempts = new AtomicInteger(0);

        // WHEN
        var result = retry.execute(() -> {
            if (attempts.incrementAndGet() <= 1) {
                throw new RuntimeException(
                        "outer", new RuntimeException("middle", new IllegalStateException("transient")));
            }
            return "recovered";
        });

        // THEN
        assertThat(result).isEqualTo("recovered");

        // AND
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void execute_throws_after_max_attempts_exhausted() {
        // GIVEN
        var config = new RetryConfig(1, Duration.ZERO);
        var retry = Retry.with(Map.of(IllegalStateException.class, config), "TestAction");
        var attempts = new AtomicInteger(0);

        // WHEN / THEN
        assertThatThrownBy(() -> retry.execute(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("always fails");
                }))
                .isInstanceOf(IllegalStateException.class);

        // AND - 1 initial + 1 retry = 2 total attempts
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void execute_does_not_retry_unconfigured_exception_type() {
        // GIVEN
        var config = new RetryConfig(3, Duration.ZERO);
        var retry = Retry.with(Map.of(IllegalStateException.class, config), "TestAction");
        var attempts = new AtomicInteger(0);

        // WHEN / THEN
        assertThatThrownBy(() -> retry.execute(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalArgumentException("wrong type");
                }))
                .isInstanceOf(IllegalArgumentException.class);

        // AND
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void execute_does_not_retry_unrelated_wrapper_exception() {
        // GIVEN
        var config = new RetryConfig(3, Duration.ZERO);
        var retry = Retry.with(Map.of(IllegalStateException.class, config), "TestAction");
        var attempts = new AtomicInteger(0);

        // WHEN / THEN
        assertThatThrownBy(() -> retry.execute(() -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("unrelated", new IllegalArgumentException("wrong type"));
                }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("unrelated");

        // AND
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void execute_does_not_retry_configured_exception_when_only_suppressed() {
        // GIVEN
        var config = new RetryConfig(3, Duration.ZERO);
        var retry = Retry.with(Map.of(IllegalStateException.class, config), "TestAction");
        var attempts = new AtomicInteger(0);

        // WHEN / THEN
        assertThatThrownBy(() -> retry.execute(() -> {
                    attempts.incrementAndGet();
                    var exception = new RuntimeException("main failure");
                    exception.addSuppressed(new IllegalStateException("suppressed retryable"));
                    throw exception;
                }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("main failure");

        // AND
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void execute_does_not_retry_configured_superclass_when_only_subclass_is_thrown() {
        // GIVEN
        var config = new RetryConfig(3, Duration.ZERO);
        var retry = Retry.with(Map.of(RuntimeException.class, config), "TestAction");
        var attempts = new AtomicInteger(0);

        // WHEN / THEN
        assertThatThrownBy(() -> retry.execute(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("exact class not configured");
                }))
                .isInstanceOf(IllegalStateException.class);

        // AND
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void execute_does_not_loop_forever_on_cyclic_cause_chain() {
        // GIVEN
        var config = new RetryConfig(3, Duration.ZERO);
        var retry = Retry.with(Map.of(IllegalStateException.class, config), "TestAction");
        var attempts = new AtomicInteger(0);

        // WHEN / THEN
        assertThatThrownBy(() -> retry.execute(() -> {
                    attempts.incrementAndGet();
                    throw new CyclicCauseException("cycle");
                }))
                .isInstanceOf(CyclicCauseException.class);

        // AND
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void execute_with_zero_max_attempts_does_not_retry() {
        // GIVEN
        var config = new RetryConfig(0, Duration.ZERO);
        var retry = Retry.with(Map.of(IllegalStateException.class, config), "TestAction");
        var attempts = new AtomicInteger(0);

        // WHEN / THEN
        assertThatThrownBy(() -> retry.execute(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("no retry");
                }))
                .isInstanceOf(IllegalStateException.class);

        // AND
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void execute_retries_exact_number_of_times() throws Exception {
        // GIVEN
        var config = new RetryConfig(3, Duration.ZERO);
        var retry = Retry.with(Map.of(IllegalStateException.class, config), "TestAction");
        var attempts = new AtomicInteger(0);

        // WHEN
        var result = retry.execute(() -> {
            if (attempts.incrementAndGet() <= 3) {
                throw new IllegalStateException("transient");
            }
            return "ok";
        });

        // THEN
        assertThat(result).isEqualTo("ok");

        // AND - 1 initial + 3 retries = 4 total
        assertThat(attempts.get()).isEqualTo(4);
    }

    @Test
    void execute_restores_interrupt_flag_when_retry_delay_is_interrupted() {
        // GIVEN
        var config = new RetryConfig(1, Duration.ofSeconds(1));
        var retry = Retry.with(Map.of(IllegalStateException.class, config), "TestAction");
        var attempts = new AtomicInteger(0);

        try {
            Thread.currentThread().interrupt();

            // WHEN / THEN
            assertThatThrownBy(() -> retry.execute(() -> {
                        attempts.incrementAndGet();
                        throw new IllegalStateException("transient");
                    }))
                    .isInstanceOf(InterruptedException.class);

            // AND
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(attempts.get()).isEqualTo(1);
        } finally {
            Thread.interrupted();
        }
    }

    private static final class CyclicCauseException extends Exception {
        private CyclicCauseException(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
