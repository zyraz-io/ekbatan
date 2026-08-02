package io.ekbatan.core.action.persister.event.single_table_json;

import static io.ekbatan.core.action.persister.event.single_table_json.EventEntity.createEventEntity;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Pins the invariant that a freshly written outbox row is never already delivered.
 *
 * <p>This looks like a test of a builder default, and locally it is. It exists because something
 * outside this module depends on it: the Debezium SMTs
 * ({@code io.ekbatan.events.streaming.debeziumsmt.common.OutboxRecords}) publish a row only when
 * {@code delivered} is false, whenever the connector has unwrapped Debezium's envelope and there
 * is therefore no {@code op} to filter on. In that configuration {@code delivered = false} is the
 * only remaining signal that says "this is the insert, not the fanout's UPDATE".
 *
 * <p>So if a future change makes the persister insert rows with {@code delivered = true} - an
 * optimisation like "no in-process handlers are registered, so mark it delivered up front" is the
 * obvious way it would happen - those SMTs would silently stop publishing anything at all. That
 * failure is invisible: the connector stays RUNNING and the topic simply stays empty.
 *
 * <p>This test is the tripwire. If it fails, do not simply update it: either keep the invariant,
 * or change the SMTs to stop relying on it and update the unwrapped-records section of
 * {@code docs/events/event-streaming.md} to match.
 */
class EventEntityDeliveredDefaultTest {

    @Test
    void a_newly_persisted_event_row_is_not_marked_delivered() {
        var entity = newEntity().build();

        assertThat(entity.delivered)
                .as("the Debezium SMTs treat delivered = false as the signal that a row is a new"
                        + " event; see the class javadoc before changing this")
                .isFalse();
    }

    @Test
    void the_flag_is_still_settable_for_the_fanout_that_owns_it() {
        // Not testing the default here - just that the one legitimate writer can still set it,
        // so the invariant above is about who writes true, not about the flag being immutable.
        assertThat(newEntity().delivered(true).build().delivered).isTrue();
    }

    private static EventEntity.Builder newEntity() {
        var now = Instant.now();
        return createEventEntity(
                UUID.randomUUID(),
                "test-namespace",
                UUID.randomUUID(),
                "TestAction",
                JsonNodeFactory.instance.objectNode(),
                now,
                now,
                "model-1",
                "TestModel",
                "TestEvent",
                JsonNodeFactory.instance.objectNode(),
                now);
    }
}
