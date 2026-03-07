package io.ekbatan.core.action.persister.event.single_table;

import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.test.event.TestModelEvent;
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

    @Test
    void should_persist_action_event_with_model_events() {
        // GIVEN
        var objectMapper = new ObjectMapper();
        var persister = new SingleTableEventPersister(transactionManager, objectMapper);
        var actionEventRepo = new ActionEventEntityRepository(transactionManager, objectMapper);
        var existingIds = actionEventRepo.findAll().stream().map(e -> e.id).toList();
        var modelEvents = List.<ModelEvent<?>>of(
                new TestModelEvent(UUID.randomUUID().toString(), "test event 1"),
                new TestModelEvent(UUID.randomUUID().toString(), "test event 2"));

        // WHEN
        persister.persistActionEvents("TestAction", Instant.now(), Instant.now(), new Object() {}, modelEvents);

        // THEN
        var newActionEvent = actionEventRepo.findAll().stream()
                .filter(e -> !existingIds.contains(e.id))
                .findFirst()
                .orElseThrow();
        assertThat(newActionEvent.actionName).isEqualTo("TestAction");
        assertThat(newActionEvent.actionParams).isNotNull();
        assertThat(newActionEvent.modelEvents).hasSize(2);
        assertThat(newActionEvent.modelEvents).allSatisfy(e -> {
            assertThat(e.eventType).isEqualTo("TestModelEvent");
            assertThat(e.modelType).isEqualTo("Object");
            assertThat(e.payload).isNotNull();
        });
    }

    @Test
    void should_persist_action_event_without_model_events() {
        // GIVEN
        var objectMapper = new ObjectMapper();
        var persister = new SingleTableEventPersister(transactionManager, objectMapper);
        var actionEventRepo = new ActionEventEntityRepository(transactionManager, objectMapper);
        var existingIds = actionEventRepo.findAll().stream().map(e -> e.id).toList();

        // WHEN
        persister.persistActionEvents("TestAction", Instant.now(), Instant.now(), new Object() {}, List.of());

        // THEN
        var newActionEvent = actionEventRepo.findAll().stream()
                .filter(e -> !existingIds.contains(e.id))
                .findFirst()
                .orElseThrow();
        assertThat(newActionEvent.actionName).isEqualTo("TestAction");
        assertThat(newActionEvent.modelEvents).isEmpty();
    }
}
