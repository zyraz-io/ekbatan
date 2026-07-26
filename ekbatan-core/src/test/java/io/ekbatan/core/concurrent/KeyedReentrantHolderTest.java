package io.ekbatan.core.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KeyedReentrantHolderTest {

    @Test
    void registration_failure_does_not_leave_stale_reentry() {
        var holder = new KeyedReentrantHolder<String>(null);

        assertThatThrownBy(() -> holder.register("k", "payload", Duration.ofSeconds(1), (payload, reason) -> {}))
                .isInstanceOf(NullPointerException.class);

        assertThat(holder.tryReenter("k")).isEmpty();
    }

    @Test
    void registration_failure_invokes_release_callback_with_close() {
        var holder = new KeyedReentrantHolder<String>(null);
        var seen = new AtomicReference<KeyedReentrantHolder.ReleaseReason>();

        assertThatThrownBy(() -> holder.register("k", "payload", Duration.ofSeconds(1), (payload, reason) -> {
                    assertThat(payload).isEqualTo("payload");
                    seen.set(reason);
                }))
                .isInstanceOf(NullPointerException.class);

        assertThat(seen.get()).isEqualTo(KeyedReentrantHolder.ReleaseReason.CLOSE);
    }

    @Test
    void watchdog_fires_with_the_watchdog_reason() throws Exception {
        var holder = new KeyedReentrantHolder<String>("wd");
        var seen = new AtomicReference<KeyedReentrantHolder.ReleaseReason>();
        var fired = new CountDownLatch(1);

        var lease = holder.register("k", "payload", Duration.ofMillis(50), (payload, reason) -> {
            seen.set(reason);
            fired.countDown();
        });

        assertThat(fired.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get()).isEqualTo(KeyedReentrantHolder.ReleaseReason.WATCHDOG);
        assertThat(lease.isHeld()).isFalse();
        assertThat(holder.tryReenter("k")).isEmpty();
    }

    /**
     * A backend that refuses the watchdog's release - the JDBC providers throw
     * RuntimeException("Failed to release connection") here - must not escape the watchdog thread.
     * Uncaught, it reaches only the default handler and stderr, never SLF4J.
     */
    @Test
    void watchdog_release_failure_does_not_escape_the_watchdog_thread() throws Exception {
        var previous = Thread.getDefaultUncaughtExceptionHandler();
        var uncaught = new AtomicReference<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> uncaught.set(e));
        try {
            var holder = new KeyedReentrantHolder<String>("wd");
            var attempted = new CountDownLatch(1);

            holder.register("k", "payload", Duration.ofMillis(50), (payload, reason) -> {
                attempted.countDown();
                throw new RuntimeException("Failed to release connection");
            });

            assertThat(attempted.await(10, TimeUnit.SECONDS)).isTrue();
            // Give an escaping throwable time to reach the handler before asserting its absence.
            Thread.sleep(200);
            assertThat(uncaught.get()).isNull();
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void registration_failure_suppresses_cleanup_exception() {
        var holder = new KeyedReentrantHolder<String>(null);
        var cleanupFailure = new RuntimeException("backend release blew up");

        assertThatThrownBy(() -> holder.register("k", "payload", Duration.ofSeconds(1), (payload, reason) -> {
                    throw cleanupFailure;
                }))
                .isInstanceOf(NullPointerException.class)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(cleanupFailure));
    }
}
