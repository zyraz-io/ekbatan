package io.ekbatan.core.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
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
