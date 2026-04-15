package io.ekbatan.core.action.persister.event.single_table_json;

import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.core.test.event.TestActionParams;
import io.ekbatan.core.test.event.TestModelEvent;
import io.ekbatan.core.test.event.TestStatusChangedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

public abstract class BaseSingleTableJsonEventPersisterTest {

    protected final TransactionManager transactionManager;
    protected final DatabaseRegistry databaseRegistry;

    public BaseSingleTableJsonEventPersisterTest(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
        this.databaseRegistry =
                databaseRegistry().withDatabase(transactionManager).build();
    }

    private SingleTableJsonEventPersister createPersister(ObjectMapper objectMapper) {
        return new SingleTableJsonEventPersister(databaseRegistry, objectMapper);
    }

    private EventEntityRepository createRepo() {
        return new EventEntityRepository(databaseRegistry);
    }

    private List<EventEntity> persistAndFetchEvents(
            ObjectMapper objectMapper,
            String namespace,
            String actionName,
            Instant startedDate,
            Instant completionDate,
            Object actionParams,
            List<ModelEvent<?>> modelEvents) {
        var persister = createPersister(objectMapper);
        var repo = createRepo();
        var existingIds =
                repo.findAll(ShardIdentifier.DEFAULT).stream().map(e -> e.id).toList();

        persister.persistActionEvents(
                namespace,
                actionName,
                startedDate,
                completionDate,
                actionParams,
                modelEvents,
                ShardIdentifier.DEFAULT,
                UUID.randomUUID());

        return repo.findAll(ShardIdentifier.DEFAULT).stream()
                .filter(e -> !existingIds.contains(e.id))
                .toList();
    }

    @Test
    void should_persist_single_event_with_denormalized_action_context() {
        // GIVEN
        var objectMapper = new ObjectMapper();
        var startedDate = Instant.parse("2025-01-15T10:00:00Z");
        var completionDate = Instant.parse("2025-01-15T10:00:01Z");
        var modelId = UUID.randomUUID().toString();
        var referenceId = UUID.randomUUID();

        var event = new TestModelEvent(
                modelId,
                "wallet created",
                Instant.now(),
                42,
                new BigDecimal("199.99"),
                referenceId,
                true,
                List.of("vip"));

        // WHEN
        var events = persistAndFetchEvents(
                objectMapper,
                "com.example.finance",
                "CreateWalletAction",
                startedDate,
                completionDate,
                new TestActionParams("create", 5),
                List.of(event));

        // THEN
        assertThat(events).hasSize(1);
        var persisted = events.getFirst();

        assertThat(persisted.id).isNotNull();
        assertThat(persisted.namespace).isEqualTo("com.example.finance");
        assertThat(persisted.actionName).isEqualTo("CreateWalletAction");
        assertThat(persisted.startedDate).isEqualTo(startedDate);
        assertThat(persisted.completionDate).isEqualTo(completionDate);
        assertThatJson(persisted.actionParams).isEqualTo("""
                {"action":"create","priority":5}""");

        assertThat(persisted.modelId).isEqualTo(modelId);
        assertThat(persisted.modelType).isEqualTo("Object");
        assertThat(persisted.eventType).isEqualTo("TestModelEvent");
        assertThat(persisted.eventDate).isEqualTo(completionDate);
        assertThatJson(persisted.payload).node("description").isEqualTo("wallet created");
    }

    @Test
    void should_persist_multiple_events_with_shared_action_context() {
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
        var persisted = persistAndFetchEvents(
                objectMapper,
                "com.example.finance",
                "BatchAction",
                Instant.now(),
                Instant.now(),
                new TestActionParams("batch", 3),
                events);

        // THEN
        assertThat(persisted).hasSize(2);

        // All share the same action context
        var actionIds = persisted.stream().map(e -> e.actionId).distinct().toList();
        assertThat(actionIds).hasSize(1);
        assertThat(persisted).allSatisfy(e -> {
            assertThat(e.namespace).isEqualTo("com.example.finance");
            assertThat(e.actionName).isEqualTo("BatchAction");
            assertThat(e.eventType).isEqualTo("TestModelEvent");
        });

        // Each has distinct id
        var ids = persisted.stream().map(e -> e.id).distinct().toList();
        assertThat(ids).hasSize(2);
    }

    @Test
    void should_persist_sentinel_row_for_zero_events() {
        // GIVEN
        var objectMapper = new ObjectMapper();
        var startedDate = Instant.parse("2025-06-01T12:00:00Z");
        var completionDate = Instant.parse("2025-06-01T12:00:01Z");

        // WHEN
        var persisted = persistAndFetchEvents(
                objectMapper,
                "com.example.finance",
                "NoOpAction",
                startedDate,
                completionDate,
                new TestActionParams("noop", 0),
                List.of());

        // THEN — sentinel row with null event fields
        assertThat(persisted).hasSize(1);
        var sentinel = persisted.getFirst();

        assertThat(sentinel.namespace).isEqualTo("com.example.finance");
        assertThat(sentinel.actionName).isEqualTo("NoOpAction");
        assertThat(sentinel.startedDate).isEqualTo(startedDate);
        assertThat(sentinel.completionDate).isEqualTo(completionDate);
        assertThatJson(sentinel.actionParams).isEqualTo("""
                {"action":"noop","priority":0}""");

        assertThat(sentinel.modelId).isNull();
        assertThat(sentinel.modelType).isNull();
        assertThat(sentinel.eventType).isNull();
        assertThat(sentinel.payload).isNull();
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
        var persisted = persistAndFetchEvents(
                objectMapper,
                "com.example.finance",
                "MixedAction",
                Instant.now(),
                Instant.now(),
                new TestActionParams("mixed", 2),
                events);

        // THEN
        assertThat(persisted).hasSize(2);

        var byType = persisted.stream().collect(java.util.stream.Collectors.toMap(e -> e.eventType, e -> e));

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
    void should_persist_namespace_on_every_row() {
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
        var persisted = persistAndFetchEvents(
                objectMapper,
                "com.example.payments",
                "MultiAction",
                Instant.now(),
                Instant.now(),
                new TestActionParams("multi", 0),
                events);

        // THEN
        assertThat(persisted).hasSize(2);
        assertThat(persisted).allSatisfy(e -> assertThat(e.namespace).isEqualTo("com.example.payments"));
    }

    @Test
    void should_find_events_by_action_id() {
        // GIVEN
        var objectMapper = new ObjectMapper();
        var repo = createRepo();
        var persister = createPersister(objectMapper);
        var referenceId = UUID.randomUUID();
        var actionEventId = UUID.randomUUID();

        var event = new TestModelEvent(
                UUID.randomUUID().toString(),
                "findable",
                Instant.now(),
                1,
                BigDecimal.ONE,
                referenceId,
                true,
                List.of());

        persister.persistActionEvents(
                "com.example.finance",
                "FindAction",
                Instant.now(),
                Instant.now(),
                new TestActionParams("find", 1),
                List.of(event),
                ShardIdentifier.DEFAULT,
                actionEventId);

        // WHEN
        var found = repo.findByActionId(actionEventId, ShardIdentifier.DEFAULT);

        // THEN
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().actionId).isEqualTo(actionEventId);
        assertThat(found.getFirst().actionName).isEqualTo("FindAction");
        assertThatJson(found.getFirst().payload).node("description").isEqualTo("findable");
    }
}
