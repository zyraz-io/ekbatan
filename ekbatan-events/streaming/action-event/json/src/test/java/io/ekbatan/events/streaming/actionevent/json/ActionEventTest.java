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
        var sentinel = ActionEvent.actionEvent()
                .id(UUID.randomUUID())
                .namespace("wallet")
                .actionId(UUID.randomUUID())
                .actionName("NoOpAction")
                .actionParams(MAPPER.createObjectNode())
                .startedDate(Instant.parse("2026-08-02T10:15:30.123456Z"))
                .completionDate(Instant.parse("2026-08-02T10:15:30.223456Z"))
                .eventDate(Instant.parse("2026-08-02T10:15:30.123456Z"))
                .delivered(false)
                .build();

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
        return ActionEvent.actionEvent()
                .id(UUID.fromString("0198f4a2-1c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                .namespace("wallet")
                .actionId(UUID.fromString("0198f4a2-1c3d-7e4f-8a9b-0c1d2e3f4a5c"))
                .actionName("DepositAction")
                .actionParams(params)
                .startedDate(Instant.parse("2026-08-02T10:15:30.123456Z"))
                .completionDate(Instant.parse("2026-08-02T10:15:30.223456Z"))
                .modelId("wallet-1")
                .modelType("Wallet")
                .eventType("WalletMoneyDepositedEvent")
                .payload(payload)
                .eventDate(Instant.parse("2026-08-02T10:15:30.123456Z"))
                .delivered(false)
                .build();
    }

    /**
     * Every setter writes the field it names.
     *
     * <p>Reading raw CDC output means mapping onto this type by hand, which is why the builder
     * exists at all. A setter wired to the wrong field would produce a quietly wrong event rather
     * than a compile error, so all thirteen are checked rather than trusted to look right.
     */
    @Test
    void every_setter_lands_in_its_own_field() {
        // Distinct literal values, not values read back out of another built instance - comparing
        // the builder against itself would pass even with two setters wired to the same field.
        var id = UUID.fromString("0198f4a2-1c3d-7e4f-8a9b-000000000001");
        var actionId = UUID.fromString("0198f4a2-1c3d-7e4f-8a9b-000000000002");
        var params = MAPPER.createObjectNode();
        params.put("which", "params");
        var payload = MAPPER.createObjectNode();
        payload.put("which", "payload");

        var event = ActionEvent.actionEvent()
                .id(id)
                .namespace("the-namespace")
                .actionId(actionId)
                .actionName("TheActionName")
                .actionParams(params)
                .startedDate(Instant.parse("2001-01-01T00:00:00Z"))
                .completionDate(Instant.parse("2002-01-01T00:00:00Z"))
                .modelId("the-model-id")
                .modelType("TheModelType")
                .eventType("TheEventType")
                .payload(payload)
                .eventDate(Instant.parse("2003-01-01T00:00:00Z"))
                .delivered(true)
                .build();

        assertThat(event.id).isEqualTo(id);
        assertThat(event.namespace).isEqualTo("the-namespace");
        assertThat(event.actionId).isEqualTo(actionId);
        assertThat(event.actionName).isEqualTo("TheActionName");
        assertThat(event.actionParams.get("which").asString()).isEqualTo("params");
        assertThat(event.startedDate).isEqualTo(Instant.parse("2001-01-01T00:00:00Z"));
        assertThat(event.completionDate).isEqualTo(Instant.parse("2002-01-01T00:00:00Z"));
        assertThat(event.modelId).isEqualTo("the-model-id");
        assertThat(event.modelType).isEqualTo("TheModelType");
        assertThat(event.eventType).isEqualTo("TheEventType");
        assertThat(event.payload.get("which").asString()).isEqualTo("payload");
        assertThat(event.eventDate).isEqualTo(Instant.parse("2003-01-01T00:00:00Z"));
        assertThat(event.delivered).isTrue();
    }

    /**
     * The pairs a positional call can transpose without the compiler noticing - two adjacent
     * Strings and two adjacent Instants. Naming them is the whole point, so this pins that the
     * builder keeps them apart.
     */
    @Test
    void the_transposable_pairs_land_in_their_own_fields() {
        var started = Instant.parse("2020-01-01T00:00:00Z");
        var completed = Instant.parse("2030-01-01T00:00:00Z");

        var event = ActionEvent.actionEvent()
                .modelType("Wallet")
                .eventType("WalletMoneyDepositedEvent")
                .startedDate(started)
                .completionDate(completed)
                .build();

        assertThat(event.modelType).isEqualTo("Wallet");
        assertThat(event.eventType).isEqualTo("WalletMoneyDepositedEvent");
        assertThat(event.startedDate).isEqualTo(started);
        assertThat(event.completionDate).isEqualTo(completed);
    }

    /**
     * Nothing is required. A sentinel row - an action that emitted no model event - legitimately
     * carries no modelId, modelType, eventType or payload, so a builder that demanded them would
     * reject valid traffic.
     */
    @Test
    void an_unset_field_stays_null_rather_than_failing() {
        var event = ActionEvent.actionEvent().namespace("wallet").build();

        assertThat(event.namespace).isEqualTo("wallet");
        assertThat(event.modelId).isNull();
        assertThat(event.modelType).isNull();
        assertThat(event.eventType).isNull();
        assertThat(event.payload).isNull();
        assertThat(event.delivered).isFalse();
    }
}
