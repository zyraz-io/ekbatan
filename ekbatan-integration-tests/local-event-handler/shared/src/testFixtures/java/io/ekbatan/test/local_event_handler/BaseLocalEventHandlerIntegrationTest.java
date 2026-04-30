package io.ekbatan.test.local_event_handler;

import static io.ekbatan.core.action.ActionExecutor.Builder.actionExecutor;
import static io.ekbatan.core.action.ActionRegistry.Builder.actionRegistry;
import static io.ekbatan.core.repository.RepositoryRegistry.Builder.repositoryRegistry;
import static io.ekbatan.events.localeventhandler.EventHandlerRegistry.eventHandlerRegistry;
import static io.ekbatan.events.localeventhandler.job.EventFanoutJob.eventFanoutJob;
import static io.ekbatan.events.localeventhandler.job.EventHandlingJob.eventHandlingJob;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.repository.AbstractRepository;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.core.shard.ShardedUUID;
import io.ekbatan.events.localeventhandler.LocalEventHandlerPersister;
import io.ekbatan.test.local_event_handler.audit.models.AuditEntry;
import io.ekbatan.test.local_event_handler.note.action.NoteCreateAction;
import io.ekbatan.test.local_event_handler.note.handler.NoteCreatedAuditEntryHandler;
import io.ekbatan.test.local_event_handler.note.handler.NoteCreatedAuditHandler;
import io.ekbatan.test.local_event_handler.note.models.Note;
import io.ekbatan.test.local_event_handler.widget.action.WidgetCreateAction;
import io.ekbatan.test.local_event_handler.widget.handler.AlwaysFailingWidgetCreatedHandler;
import io.ekbatan.test.local_event_handler.widget.handler.FlakyWidgetCreatedHandler;
import io.ekbatan.test.local_event_handler.widget.handler.SlowAlwaysFailingHandler;
import io.ekbatan.test.local_event_handler.widget.handler.WidgetCreatedAutoNoteHandler;
import io.ekbatan.test.local_event_handler.widget.handler.WidgetCreatedEmailHandler;
import io.ekbatan.test.local_event_handler.widget.handler.WidgetCreatedIndexerHandler;
import io.ekbatan.test.local_event_handler.widget.models.Widget;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

public abstract class BaseLocalEventHandlerIntegrationTest {

    protected final DatabaseRegistry databaseRegistry;
    protected final List<ShardIdentifier> shards;
    protected final AbstractRepository<Widget, ?, ?, UUID> widgetRepo;
    protected final AbstractRepository<Note, ?, ?, UUID> noteRepo;
    protected final AbstractRepository<AuditEntry, ?, ?, UUID> auditEntryRepo;
    protected final ObjectMapper objectMapper;
    protected final ActionExecutor executor;

    protected BaseLocalEventHandlerIntegrationTest(
            DatabaseRegistry databaseRegistry,
            List<ShardIdentifier> shards,
            AbstractRepository<Widget, ?, ?, UUID> widgetRepo,
            AbstractRepository<Note, ?, ?, UUID> noteRepo,
            AbstractRepository<AuditEntry, ?, ?, UUID> auditEntryRepo) {
        this.databaseRegistry = databaseRegistry;
        this.shards = List.copyOf(shards);
        this.widgetRepo = widgetRepo;
        this.noteRepo = noteRepo;
        this.auditEntryRepo = auditEntryRepo;
        this.objectMapper = new ObjectMapper();

        var clock = Clock.systemUTC();
        this.executor = actionExecutor()
                .namespace("test.local-event-handler")
                .databaseRegistry(databaseRegistry)
                .objectMapper(objectMapper)
                .repositoryRegistry(repositoryRegistry()
                        .withModelRepository(Widget.class, widgetRepo)
                        .withModelRepository(Note.class, noteRepo)
                        .build())
                .actionRegistry(actionRegistry()
                        .withAction(WidgetCreateAction.class, new WidgetCreateAction(clock))
                        .withAction(NoteCreateAction.class, new NoteCreateAction(clock))
                        .build())
                .eventPersister(new LocalEventHandlerPersister(databaseRegistry, objectMapper))
                .build();
    }

    @BeforeEach
    void cleanTables() throws Exception {
        truncateAllTables();
    }

    /** Truncate every table on every shard. */
    protected abstract void truncateAllTables() throws Exception;

    /** Count notifications in {@code state} on a single shard. */
    protected abstract long countNotificationsOnShard(String state, ShardIdentifier shard) throws Exception;

    /** Count {@code eventlog.events} rows by {@code delivered} flag on a single shard. */
    protected abstract long countEventsDeliveredOnShard(boolean delivered, ShardIdentifier shard) throws Exception;

    /** Sum across all shards. */
    protected long countNotifications(String state) throws Exception {
        long total = 0;
        for (var shard : shards) total += countNotificationsOnShard(state, shard);
        return total;
    }

    /** Sum across all shards. */
    protected long countEventsDelivered(boolean delivered) throws Exception {
        long total = 0;
        for (var shard : shards) total += countEventsDeliveredOnShard(delivered, shard);
        return total;
    }

    /** Cycle through the configured shards by index, so multi-shard tests spread items evenly. */
    private ShardIdentifier shardFor(int index) {
        return shards.get(index % shards.size());
    }

    private static ShardIdentifier shardOf(Widget widget) {
        return ShardedUUID.from(widget.id.getValue()).resolveShardIdentifier();
    }

    @Test
    void happy_path_action_to_dispatch_invokes_every_subscribed_handler() throws Exception {
        // GIVEN
        var emailHandler = new WidgetCreatedEmailHandler();
        var indexerHandler = new WidgetCreatedIndexerHandler();
        var registry = eventHandlerRegistry()
                .withHandler(emailHandler)
                .withHandler(indexerHandler)
                .build();

        var fanoutJob = eventFanoutJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(registry)
                .clock(Clock.systemUTC())
                .build();
        var handlingJob = eventHandlingJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(registry)
                .objectMapper(objectMapper)
                .clock(Clock.systemUTC())
                .build();

        // WHEN — three widgets, one per (cycled) shard
        var w1 = executor.execute(
                () -> "tester", WidgetCreateAction.class, new WidgetCreateAction.Params(shardFor(0), "alpha", "red"));
        var w2 = executor.execute(
                () -> "tester", WidgetCreateAction.class, new WidgetCreateAction.Params(shardFor(1), "beta", "green"));
        var w3 = executor.execute(
                () -> "tester", WidgetCreateAction.class, new WidgetCreateAction.Params(shardFor(2), "gamma", "blue"));

        // THEN — every widget landed on the shard encoded in its ID
        assertThat(shardOf(w1)).isEqualTo(shardFor(0));
        assertThat(shardOf(w2)).isEqualTo(shardFor(1));
        assertThat(shardOf(w3)).isEqualTo(shardFor(2));
        assertThat(widgetRepo.findById(w1.id.getValue())).isPresent();
        assertThat(widgetRepo.findById(w2.id.getValue())).isPresent();
        assertThat(widgetRepo.findById(w3.id.getValue())).isPresent();
        assertThat(countEventsDelivered(false)).isEqualTo(3);
        assertThat(countEventsDelivered(true)).isZero();
        assertThat(countNotifications("PENDING")).isZero();

        // AND — each widget's source event is on its own shard
        assertThat(countEventsDeliveredOnShard(false, shardFor(0)))
                .as("undelivered events on shard 0")
                .isPositive();
        assertThat(countEventsDeliveredOnShard(false, shardFor(1)))
                .as("undelivered events on shard 1")
                .isPositive();

        // WHEN
        fanoutJob.drainOneRound();

        // THEN — fan-out flipped delivered=true on every shard, materialized 6 notifications
        assertThat(countEventsDelivered(false)).isZero();
        assertThat(countEventsDelivered(true)).isEqualTo(3);
        assertThat(countNotifications("PENDING")).isEqualTo(6);

        // AND — each shard has exactly the notifications for its own widget × 2 handlers
        for (int i = 0; i < 3; i++) {
            assertThat(countNotificationsOnShard("PENDING", shardFor(i)))
                    .as("PENDING notifications on shard " + i)
                    .isPositive();
        }

        // WHEN
        handlingJob.drainOneRound();

        // THEN — every notification reached SUCCEEDED, both handlers received all three widgets
        assertThat(countNotifications("SUCCEEDED")).isEqualTo(6);
        assertThat(countNotifications("PENDING")).isZero();
        assertThat(emailHandler.callCount()).isEqualTo(3);
        assertThat(indexerHandler.callCount()).isEqualTo(3);

        // AND
        assertThat(emailHandler.received().stream().map(e -> e.modelId))
                .containsExactlyInAnyOrder(
                        w1.id.getValue().toString(),
                        w2.id.getValue().toString(),
                        w3.id.getValue().toString());
        assertThat(emailHandler.received().stream().map(e -> e.name))
                .containsExactlyInAnyOrder("alpha", "beta", "gamma");
        assertThat(emailHandler.received().stream().map(e -> e.color))
                .containsExactlyInAnyOrder("red", "green", "blue");
    }

    @Test
    void zero_subscribers_for_event_type_writes_no_notifications() throws Exception {
        // GIVEN
        var registry = eventHandlerRegistry().build();

        var fanoutJob = eventFanoutJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(registry)
                .clock(Clock.systemUTC())
                .build();

        // WHEN — one widget per shard
        for (int i = 0; i < shards.size(); i++) {
            executor.execute(
                    () -> "tester",
                    WidgetCreateAction.class,
                    new WidgetCreateAction.Params(shardFor(i), "name-" + i, "red"));
        }
        fanoutJob.drainOneRound();

        // THEN — each shard's events are visited (delivered=true), no notifications anywhere
        assertThat(countEventsDelivered(true)).isEqualTo(shards.size());
        assertThat(countNotifications("PENDING")).isZero();
    }

    @Test
    void flaky_handler_is_retried_until_it_succeeds() throws Exception {
        // GIVEN
        var flakyHandler = new FlakyWidgetCreatedHandler(2);
        var registry = eventHandlerRegistry().withHandler(flakyHandler).build();

        var fanoutJob = eventFanoutJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(registry)
                .clock(Clock.systemUTC())
                .build();
        var handlingJob = eventHandlingJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(registry)
                .objectMapper(objectMapper)
                .maxBackoffCap(Duration.ofMillis(10))
                .clock(Clock.systemUTC())
                .build();

        // WHEN
        executor.execute(
                () -> "tester", WidgetCreateAction.class, new WidgetCreateAction.Params(shardFor(0), "alpha", "red"));
        fanoutJob.drainOneRound();
        assertThat(countNotifications("PENDING")).isEqualTo(1);

        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> {
                    handlingJob.drainOneRound();
                    assertThat(countNotifications("SUCCEEDED")).isEqualTo(1);
                });

        // THEN
        assertThat(flakyHandler.callCount()).isEqualTo(3);
        assertThat(countNotifications("FAILED")).isZero();
    }

    @Test
    void event_past_retention_is_expired_without_invoking_handler() throws Exception {
        // GIVEN
        var emailHandler = new WidgetCreatedEmailHandler();
        var registry = eventHandlerRegistry().withHandler(emailHandler).build();

        var fanoutJob = eventFanoutJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(registry)
                .clock(Clock.systemUTC())
                .build();
        var handlingJob = eventHandlingJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(registry)
                .objectMapper(objectMapper)
                .retentionWindow(Duration.ofSeconds(7))
                .clock(Clock.fixed(Instant.now().plusSeconds(60), ZoneOffset.UTC))
                .build();

        // WHEN
        executor.execute(
                () -> "tester", WidgetCreateAction.class, new WidgetCreateAction.Params(shardFor(0), "alpha", "red"));
        fanoutJob.drainOneRound();
        assertThat(countNotifications("PENDING")).isEqualTo(1);

        handlingJob.drainOneRound();

        // THEN
        assertThat(countNotifications("EXPIRED")).isEqualTo(1);
        assertThat(countNotifications("SUCCEEDED")).isZero();
        assertThat(emailHandler.callCount()).isZero();
    }

    @Test
    void always_failing_handler_eventually_expires_and_stops_retrying() throws Exception {
        // GIVEN
        var failingHandler = new AlwaysFailingWidgetCreatedHandler();
        var registry = eventHandlerRegistry().withHandler(failingHandler).build();

        var fanoutJob = eventFanoutJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(registry)
                .clock(Clock.systemUTC())
                .build();
        var handlingJob = eventHandlingJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(registry)
                .objectMapper(objectMapper)
                .maxBackoffCap(Duration.ofMillis(5))
                .retentionWindow(Duration.ofMillis(200))
                .clock(Clock.systemUTC())
                .build();

        // WHEN
        executor.execute(
                () -> "tester", WidgetCreateAction.class, new WidgetCreateAction.Params(shardFor(0), "alpha", "red"));
        fanoutJob.drainOneRound();

        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> {
                    handlingJob.drainOneRound();
                    assertThat(countNotifications("EXPIRED")).isEqualTo(1);
                });

        // THEN
        assertThat(countNotifications("FAILED")).isZero();
        assertThat(countNotifications("SUCCEEDED")).isZero();

        // AND
        var callsBefore = failingHandler.callCount();
        handlingJob.drainOneRound();
        assertThat(failingHandler.callCount()).isEqualTo(callsBefore);
    }

    @Test
    void preflight_expiration_handles_multiple_events_in_one_batch() throws Exception {
        // GIVEN
        var emailHandler = new WidgetCreatedEmailHandler();
        var registry = eventHandlerRegistry().withHandler(emailHandler).build();

        var fanoutJob = eventFanoutJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(registry)
                .clock(Clock.systemUTC())
                .build();
        var handlingJob = eventHandlingJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(registry)
                .objectMapper(objectMapper)
                .retentionWindow(Duration.ofSeconds(7))
                .clock(Clock.fixed(Instant.now().plusSeconds(60), ZoneOffset.UTC))
                .build();

        // WHEN — three widgets across shards
        executor.execute(
                () -> "tester", WidgetCreateAction.class, new WidgetCreateAction.Params(shardFor(0), "alpha", "red"));
        executor.execute(
                () -> "tester", WidgetCreateAction.class, new WidgetCreateAction.Params(shardFor(1), "beta", "green"));
        executor.execute(
                () -> "tester", WidgetCreateAction.class, new WidgetCreateAction.Params(shardFor(2), "gamma", "blue"));
        fanoutJob.drainOneRound();
        assertThat(countNotifications("PENDING")).isEqualTo(3);

        handlingJob.drainOneRound();

        // THEN
        assertThat(countNotifications("EXPIRED")).isEqualTo(3);
        assertThat(countNotifications("PENDING")).isZero();
        assertThat(countNotifications("SUCCEEDED")).isZero();
        assertThat(countNotifications("FAILED")).isZero();
        assertThat(emailHandler.callCount()).isZero();
    }

    @Test
    void post_failure_expiration_handles_multiple_events_in_one_batch() throws Exception {
        // GIVEN
        var failingHandler = new AlwaysFailingWidgetCreatedHandler();
        var registry = eventHandlerRegistry().withHandler(failingHandler).build();

        var fanoutJob = eventFanoutJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(registry)
                .clock(Clock.systemUTC())
                .build();
        var handlingJob = eventHandlingJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(registry)
                .objectMapper(objectMapper)
                .maxBackoffCap(Duration.ofMillis(5))
                .retentionWindow(Duration.ofMillis(200))
                .clock(Clock.systemUTC())
                .build();

        // WHEN — three widgets across shards
        executor.execute(
                () -> "tester", WidgetCreateAction.class, new WidgetCreateAction.Params(shardFor(0), "alpha", "red"));
        executor.execute(
                () -> "tester", WidgetCreateAction.class, new WidgetCreateAction.Params(shardFor(1), "beta", "green"));
        executor.execute(
                () -> "tester", WidgetCreateAction.class, new WidgetCreateAction.Params(shardFor(2), "gamma", "blue"));
        fanoutJob.drainOneRound();
        assertThat(countNotifications("PENDING")).isEqualTo(3);

        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> {
                    handlingJob.drainOneRound();
                    assertThat(countNotifications("EXPIRED")).isEqualTo(3);
                });

        // THEN
        assertThat(countNotifications("FAILED")).isZero();
        assertThat(countNotifications("SUCCEEDED")).isZero();
        assertThat(countNotifications("PENDING")).isZero();
    }

    @Test
    void slow_failing_handler_expires_using_post_invocation_time() throws Exception {
        // GIVEN
        var slowFailing = new SlowAlwaysFailingHandler(Duration.ofMillis(100));
        var registry = eventHandlerRegistry().withHandler(slowFailing).build();

        var fanoutJob = eventFanoutJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(registry)
                .clock(Clock.systemUTC())
                .build();
        var handlingJob = eventHandlingJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(registry)
                .objectMapper(objectMapper)
                .maxBackoffCap(Duration.ofMillis(5))
                .retentionWindow(Duration.ofMillis(50))
                .clock(Clock.systemUTC())
                .build();

        // WHEN
        executor.execute(
                () -> "tester", WidgetCreateAction.class, new WidgetCreateAction.Params(shardFor(0), "alpha", "red"));
        fanoutJob.drainOneRound();
        handlingJob.drainOneRound();

        // THEN
        assertThat(slowFailing.callCount()).isEqualTo(1);
        assertThat(countNotifications("EXPIRED")).isEqualTo(1);
        assertThat(countNotifications("FAILED")).isZero();
        assertThat(countNotifications("SUCCEEDED")).isZero();
        assertThat(countNotifications("PENDING")).isZero();
    }

    @Test
    void widget_handler_chains_into_note_action_and_note_handler_writes_audit_entry() throws Exception {
        // GIVEN
        var autoNoteHandler = new WidgetCreatedAutoNoteHandler(executor);
        var noteAuditHandler = new NoteCreatedAuditHandler();
        var noteAuditEntryHandler = new NoteCreatedAuditEntryHandler(auditEntryRepo, Clock.systemUTC());
        var registry = eventHandlerRegistry()
                .withHandler(autoNoteHandler)
                .withHandler(noteAuditHandler)
                .withHandler(noteAuditEntryHandler)
                .build();

        var fanoutJob = eventFanoutJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(registry)
                .clock(Clock.systemUTC())
                .build();
        var handlingJob = eventHandlingJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(registry)
                .objectMapper(objectMapper)
                .clock(Clock.systemUTC())
                .build();

        // WHEN — widget on the LAST shard (so we exercise non-default routing when shards.size > 1)
        var widgetShard = shardFor(shards.size() - 1);
        var widget = executor.execute(
                () -> "tester", WidgetCreateAction.class, new WidgetCreateAction.Params(widgetShard, "alpha", "red"));

        fanoutJob.drainOneRound();
        handlingJob.drainOneRound();

        // THEN — Round 1: widget is on its shard, the chained Note follows the widget's shard.
        assertThat(autoNoteHandler.callCount()).isEqualTo(1);
        assertThat(shardOf(widget)).isEqualTo(widgetShard);

        var notes = noteRepo.findAll();
        assertThat(notes).hasSize(1);
        var createdNote = notes.getFirst();
        assertThat(createdNote.widgetId).isEqualTo(widget.id.getValue().toString());
        assertThat(createdNote.text).isEqualTo("auto-note for widget alpha");
        assertThat(ShardedUUID.from(createdNote.id.getValue()).resolveShardIdentifier())
                .as("note co-located with widget on the same shard")
                .isEqualTo(widgetShard);

        assertThat(auditEntryRepo.findAll()).isEmpty();

        // WHEN
        fanoutJob.drainOneRound();
        handlingJob.drainOneRound();

        // THEN — NoteCreatedEvent dispatched: recorder fires, side-effect handler writes audit
        assertThat(noteAuditHandler.callCount()).isEqualTo(1);
        var observedNoteEvent = noteAuditHandler.received().getFirst();
        assertThat(observedNoteEvent.modelId)
                .isEqualTo(createdNote.id.getValue().toString());
        assertThat(observedNoteEvent.widgetId).isEqualTo(widget.id.getValue().toString());
        assertThat(observedNoteEvent.text).isEqualTo("auto-note for widget alpha");

        // AND — audit entry written, on the same shard as the note
        var entries = auditEntryRepo.findAll();
        assertThat(entries).hasSize(1);
        var entry = entries.getFirst();
        assertThat(entry.noteId).isEqualTo(createdNote.id.getValue().toString());
        assertThat(entry.widgetId).isEqualTo(widget.id.getValue().toString());
        assertThat(ShardedUUID.from(entry.id.getValue()).resolveShardIdentifier())
                .as("audit entry co-located with note on the same shard")
                .isEqualTo(widgetShard);
    }
}
