package io.ekbatan.events.streaming.actionevent.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The published type could not be deserialized at all: no {@code @JsonCreator}, no no-arg
 * constructor, final fields, and no {@code -parameters} in the build, so Jackson had no way to
 * match JSON keys to constructor arguments and refused with "no Creators, like default
 * constructor, exist" - while the constructor's own javadoc claimed Jackson used it.
 *
 * <p>Nothing verified that claim, which is how it survived publication. This is that test.
 */
class ActionEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void an_action_event_round_trips_through_jackson() {
        var original = sample();

        var json = MAPPER.writeValueAsString(original);
        var restored = MAPPER.readValue(json, ActionEvent.class);

        assertThat(restored.id).isEqualTo(original.id);
        assertThat(restored.namespace).isEqualTo(original.namespace);
        assertThat(restored.actionId).isEqualTo(original.actionId);
        assertThat(restored.actionName).isEqualTo(original.actionName);
        assertThat(restored.actionParams).isEqualTo(original.actionParams);
        assertThat(restored.startedDate).isEqualTo(original.startedDate);
        assertThat(restored.completionDate).isEqualTo(original.completionDate);
        assertThat(restored.modelId).isEqualTo(original.modelId);
        assertThat(restored.modelType).isEqualTo(original.modelType);
        assertThat(restored.eventType).isEqualTo(original.eventType);
        assertThat(restored.payload).isEqualTo(original.payload);
        assertThat(restored.eventDate).isEqualTo(original.eventDate);
        assertThat(restored.delivered).isEqualTo(original.delivered);
    }

    /** A sentinel row - the nullable columns are null for an action that emitted no model event. */
    @Test
    void a_sentinel_event_round_trips() {
        var sentinel = new ActionEvent(
                UUID.randomUUID(),
                "wallet",
                UUID.randomUUID(),
                "NoOpAction",
                MAPPER.createObjectNode(),
                Instant.parse("2026-08-02T10:15:30.123456Z"),
                Instant.parse("2026-08-02T10:15:30.223456Z"),
                null,
                null,
                null,
                null,
                Instant.parse("2026-08-02T10:15:30.123456Z"),
                false);

        var restored = MAPPER.readValue(MAPPER.writeValueAsString(sentinel), ActionEvent.class);

        assertThat(restored.modelId).isNull();
        assertThat(restored.modelType).isNull();
        assertThat(restored.eventType).isNull();
        assertThat(restored.payload).isNull();
        assertThat(restored.actionName).isEqualTo("NoOpAction");
    }

    /** Sub-second precision has to survive the trip, not be rounded to millis. */
    @Test
    void timestamps_keep_their_precision() {
        var precise = Instant.parse("2026-08-02T10:15:30.123456Z");

        var restored = MAPPER.readValue(MAPPER.writeValueAsString(sample()), ActionEvent.class);

        assertThat(restored.startedDate).isEqualTo(precise);
    }

    private static ActionEvent sample() {
        var params = MAPPER.createObjectNode();
        params.put("walletId", "wallet-1");
        var payload = MAPPER.createObjectNode();
        payload.put("amount", "77.10");
        return new ActionEvent(
                UUID.fromString("0198f4a2-1c3d-7e4f-8a9b-0c1d2e3f4a5b"),
                "wallet",
                UUID.fromString("0198f4a2-1c3d-7e4f-8a9b-0c1d2e3f4a5c"),
                "DepositAction",
                params,
                Instant.parse("2026-08-02T10:15:30.123456Z"),
                Instant.parse("2026-08-02T10:15:30.223456Z"),
                "wallet-1",
                "Wallet",
                "WalletMoneyDepositedEvent",
                payload,
                Instant.parse("2026-08-02T10:15:30.123456Z"),
                false);
    }
}
