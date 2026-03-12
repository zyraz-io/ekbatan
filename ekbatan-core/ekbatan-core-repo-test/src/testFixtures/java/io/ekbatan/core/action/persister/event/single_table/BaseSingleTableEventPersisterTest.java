package io.ekbatan.core.action.persister.event.single_table;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.test.event.TestActionParams;
import io.ekbatan.core.test.event.TestModelEvent;
import io.ekbatan.core.test.event.TestStatusChangedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

public abstract class BaseSingleTableEventPersisterTest {

    protected final TransactionManager transactionManager;

    public BaseSingleTableEventPersisterTest(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    private SingleTableEventPersister createPersister(ObjectMapper objectMapper) {
        return new SingleTableEventPersister(transactionManager, objectMapper);
    }

    private ActionEventEntityRepository createRepo(ObjectMapper objectMapper) {
        return new ActionEventEntityRepository(transactionManager, objectMapper);
    }

    private ActionEventEntity persistAndFetch(
            ObjectMapper objectMapper,
            String actionName,
            Instant startedDate,
            Instant completionDate,
            Object actionParams,
            List<ModelEvent<?>> modelEvents) {
        var persister = createPersister(objectMapper);
        var repo = createRepo(objectMapper);
        var existingIds = repo.findAll().stream().map(e -> e.id).toList();

        persister.persistActionEvents(actionName, startedDate, completionDate, actionParams, modelEvents);

        return repo.findAll().stream()
                .filter(e -> !existingIds.contains(e.id))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void should_persist_action_event_fields() {
        // GIVEN
        var objectMapper = new ObjectMapper();
        var startedDate = Instant.parse("2025-01-15T10:00:00Z");
        var completionDate = Instant.parse("2025-01-15T10:00:01Z");

        // WHEN
        var actionEvent = persistAndFetch(
                objectMapper,
                "CreateWalletAction",
                startedDate,
                completionDate,
                new TestActionParams("create", 5),
                List.of());

        // THEN
        assertThat(actionEvent.id).isNotNull();
        assertThat(actionEvent.actionName).isEqualTo("CreateWalletAction");
        assertThat(actionEvent.startedDate).isEqualTo(startedDate);
        assertThat(actionEvent.completionDate).isEqualTo(completionDate);
        assertThat(actionEvent.modelEvents).isEmpty();
        assertThatJson(actionEvent.actionParams).isEqualTo("""
                        {"action":"create","priority":5}""");
    }

    @Test
    void should_persist_action_event_without_model_events() {
        // GIVEN
        var objectMapper = new ObjectMapper();

        // WHEN
        var actionEvent = persistAndFetch(
                objectMapper, "NoOpAction", Instant.now(), Instant.now(), new TestActionParams("noop", 0), List.of());

        // THEN
        assertThat(actionEvent.actionName).isEqualTo("NoOpAction");
        assertThat(actionEvent.modelEvents).isEmpty();
    }

    @Test
    void should_persist_single_model_event_with_payload() {
        // GIVEN
        var objectMapper = new ObjectMapper();
        var modelId = UUID.randomUUID().toString();
        var referenceId = UUID.randomUUID();
        var eventTime = Instant.parse("2025-06-01T12:30:00Z");
        var completionDate = Instant.parse("2025-06-01T12:30:01Z");

        var event = new TestModelEvent(
                modelId,
                "wallet created",
                eventTime,
                42,
                new BigDecimal("199.99"),
                referenceId,
                true,
                List.of("vip", "new"));

        // WHEN
        var actionEvent = persistAndFetch(
                objectMapper,
                "TestAction",
                Instant.parse("2025-06-01T12:30:00Z"),
                completionDate,
                new TestActionParams("test", 1),
                List.of(event));

        // THEN
        assertThat(actionEvent.modelEvents).hasSize(1);

        var persisted = actionEvent.modelEvents.getFirst();
        assertThat(persisted.id).isNotNull();
        assertThat(persisted.actionId).isEqualTo(actionEvent.id);
        assertThat(persisted.modelId).isEqualTo(modelId);
        assertThat(persisted.modelType).isEqualTo("Object");
        assertThat(persisted.eventType).isEqualTo("TestModelEvent");
        assertThat(persisted.eventDate).isEqualTo(completionDate);

        assertThatJson(persisted.payload)
                .and(
                        a -> a.node("description").isEqualTo("wallet created"),
                        a -> a.node("eventTime").isEqualTo("2025-06-01T12:30:00Z"),
                        a -> a.node("count").isEqualTo(42),
                        a -> a.node("amount").isEqualTo(199.99),
                        a -> a.node("referenceId").isEqualTo(referenceId.toString()),
                        a -> a.node("active").isEqualTo(true),
                        a -> a.node("tags").isArray().containsExactly("vip", "new"));
    }

    @Test
    void should_persist_multiple_model_events() {
        // GIVEN
        var objectMapper = new ObjectMapper();
        var modelId1 = UUID.randomUUID().toString();
        var modelId2 = UUID.randomUUID().toString();
        var referenceId = UUID.randomUUID();
        var eventTime = Instant.parse("2025-03-10T08:00:00Z");

        var events = List.<ModelEvent<?>>of(
                new TestModelEvent(
                        modelId1, "event one", eventTime, 1, new BigDecimal("10.00"), referenceId, true, List.of("a")),
                new TestModelEvent(
                        modelId2,
                        "event two",
                        eventTime,
                        2,
                        new BigDecimal("20.00"),
                        referenceId,
                        false,
                        List.of("b", "c")));

        // WHEN
        var actionEvent = persistAndFetch(
                objectMapper, "BatchAction", Instant.now(), Instant.now(), new TestActionParams("batch", 3), events);

        // THEN
        assertThat(actionEvent.modelEvents).hasSize(2);
        assertThat(actionEvent.modelEvents).allSatisfy(e -> {
            assertThat(e.eventType).isEqualTo("TestModelEvent");
            assertThat(e.modelType).isEqualTo("Object");
            assertThat(e.actionId).isEqualTo(actionEvent.id);
        });

        // AND
        var payloads = actionEvent.modelEvents.stream().map(e -> e.payload).toList();
        assertThat(payloads)
                .anySatisfy(p -> assertThatJson(p).node("description").isEqualTo("event one"));
        assertThat(payloads)
                .anySatisfy(p -> assertThatJson(p).node("description").isEqualTo("event two"));
    }

    @Test
    void should_persist_mixed_event_types() {
        // GIVEN
        var objectMapper = new ObjectMapper();
        var modelId = UUID.randomUUID().toString();
        var referenceId = UUID.randomUUID();
        var eventTime = Instant.parse("2025-04-20T14:00:00Z");

        var events = List.<ModelEvent<?>>of(
                new TestModelEvent(modelId, "created", eventTime, 1, BigDecimal.ONE, referenceId, true, List.of()),
                new TestStatusChangedEvent(modelId, "ACTIVE", "SUSPENDED"));

        // WHEN
        var actionEvent = persistAndFetch(
                objectMapper, "MixedAction", Instant.now(), Instant.now(), new TestActionParams("mixed", 2), events);

        // THEN
        assertThat(actionEvent.modelEvents).hasSize(2);

        var byType =
                actionEvent.modelEvents.stream().collect(java.util.stream.Collectors.toMap(e -> e.eventType, e -> e));

        var testModelEvent = byType.get("TestModelEvent");
        assertThat(testModelEvent).isNotNull();
        assertThat(testModelEvent.modelId).isEqualTo(modelId);
        assertThatJson(testModelEvent.payload).node("description").isEqualTo("created");

        var statusChangedEvent = byType.get("TestStatusChangedEvent");
        assertThat(statusChangedEvent).isNotNull();
        assertThat(statusChangedEvent.modelId).isEqualTo(modelId);
        assertThatJson(statusChangedEvent.payload)
                .and(a -> a.node("oldStatus").isEqualTo("ACTIVE"), a -> a.node("newStatus")
                        .isEqualTo("SUSPENDED"));
    }

    @Test
    void should_persist_model_event_ids_as_distinct_uuids() {
        // GIVEN
        var objectMapper = new ObjectMapper();
        var referenceId = UUID.randomUUID();
        var eventTime = Instant.now();

        var events = List.<ModelEvent<?>>of(
                new TestModelEvent(
                        UUID.randomUUID().toString(),
                        "e1",
                        eventTime,
                        1,
                        BigDecimal.ZERO,
                        referenceId,
                        true,
                        List.of()),
                new TestModelEvent(
                        UUID.randomUUID().toString(),
                        "e2",
                        eventTime,
                        2,
                        BigDecimal.ZERO,
                        referenceId,
                        true,
                        List.of()));

        // WHEN
        var actionEvent = persistAndFetch(
                objectMapper, "IdAction", Instant.now(), Instant.now(), new TestActionParams("ids", 0), events);

        // THEN
        var ids = actionEvent.modelEvents.stream().map(e -> e.id).toList();
        assertThat(ids).hasSize(2);
        assertThat(ids.get(0)).isNotEqualTo(ids.get(1));
    }
}
