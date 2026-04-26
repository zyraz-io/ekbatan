package io.ekbatan.events.localeventhandler.job;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.distributedjobs.DistributedJob;
import io.ekbatan.events.localeventhandler.EventHandlerRegistry;
import io.ekbatan.events.localeventhandler.model.EventNotification;
import io.ekbatan.events.localeventhandler.model.EventNotificationState;
import io.ekbatan.events.localeventhandler.repository.EventEntityRepository;
import io.ekbatan.events.localeventhandler.repository.EventNotificationRepository;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.apache.commons.lang3.Validate;

/**
 * {@link DistributedJob} that scans every shard's {@code eventlog.events} for newly-committed
 * undelivered events, materializes one {@code event_notifications} row per (event, subscribed
 * handler), and flips {@code delivered = TRUE} on the source events.
 *
 * <p>One job instance handles all shards via {@link DatabaseRegistry#allTransactionManagers()}.
 * Each {@code execute()} runs a continuous round-by-round loop: each round drains <em>one
 * batch from every shard in parallel</em> on virtual threads, waits for all shards to
 * finish, then either starts another round (if any shard had work) or sleeps for
 * {@link Builder#pollDelay} before checking again. Only shutdown causes {@code execute()}
 * to return.
 *
 * <p>Round-by-round (rather than each shard looping independently) means a shard that's been
 * drained but receives a fresh event is revisited on the very next round — the new event
 * gets picked up in the same {@code execute()} invocation rather than waiting for the next
 * scheduler tick.
 */
public final class EventFanoutJob extends DistributedJob {

    private static final Meter METER = GlobalOpenTelemetry.get().getMeter("io.ekbatan.events.localeventhandler");
    private static final LongCounter EVENTS_FANNED_OUT = METER.counterBuilder("ekbatan.events.fanned_out")
            .setDescription("Source events transitioned from undelivered to delivered by the fan-out job")
            .setUnit("{event}")
            .build();
    private static final LongCounter NOTIFICATIONS_CREATED = METER.counterBuilder(
                    "ekbatan.events.notifications_created")
            .setDescription("Notification rows materialized by the fan-out job (one per event × subscribed handler)")
            .setUnit("{notification}")
            .build();

    private static final String DEFAULT_NAME = "ekbatan-event-fanout";
    private static final Duration DEFAULT_POLL_DELAY = Duration.ofSeconds(1);
    private static final int DEFAULT_BATCH_SIZE = 200;

    private final String name;
    private final Duration pollDelay;
    private final int batchSize;
    private final DatabaseRegistry databaseRegistry;
    private final EventHandlerRegistry eventHandlerRegistry;
    private final EventEntityRepository eventEntityRepository;
    private final EventNotificationRepository eventNotificationRepository;
    private final Clock clock;

    private EventFanoutJob(Builder builder) {
        this.name = Validate.notNull(builder.name, "name is required");
        this.pollDelay = Validate.notNull(builder.pollDelay, "pollDelay is required");
        Validate.isTrue(!this.pollDelay.isNegative() && !this.pollDelay.isZero(), "pollDelay must be positive");
        this.batchSize = builder.batchSize;
        Validate.isTrue(this.batchSize > 0, "batchSize must be positive");
        this.databaseRegistry = Validate.notNull(builder.databaseRegistry, "databaseRegistry is required");
        this.eventHandlerRegistry = Validate.notNull(builder.eventHandlerRegistry, "eventHandlerRegistry is required");
        this.clock = Validate.notNull(builder.clock, "clock is required");
        this.eventEntityRepository = new EventEntityRepository(this.databaseRegistry);
        this.eventNotificationRepository = new EventNotificationRepository(this.databaseRegistry);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Schedule schedule() {
        return FixedDelay.of(pollDelay);
    }

    @Override
    public void execute(ExecutionContext ctx) {
        try {
            while (!shouldStop(ctx)) {
                final boolean anyProcessed = drainOneRound();
                if (!anyProcessed && !shouldStop(ctx)) {
                    Thread.sleep(pollDelay.toMillis());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // graceful return — db-scheduler is interrupting us during shutdown
        }
        // RuntimeException propagates: db-scheduler re-invokes execute() after FixedDelay
        // (which equals our pollDelay), giving the next attempt the same throttling as a
        // healthy idle round. The known idempotency case (replica-lag → unique-conflict on
        // the notifications insert) is handled inside the repository via
        // ON CONFLICT DO NOTHING and never reaches here.
    }

    /**
     * One round: launch one virtual-thread fork per shard, each draining a single batch.
     * Wait for all forks. Returns whether any fork processed events.
     */
    public boolean drainOneRound() throws InterruptedException {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var futures = databaseRegistry.allTransactionManagers().stream()
                    .map(tm -> executor.submit(() -> drainBatch(tm) > 0))
                    .toList();
            boolean any = false;
            for (var f : futures) {
                try {
                    any |= f.get();
                } catch (ExecutionException e) {
                    throw new RuntimeException("Fanout worker threw", e.getCause());
                }
            }
            return any;
        }
    }

    private int drainBatch(TransactionManager tm) {
        // One transaction per batch: select undelivered → insert notifications → mark delivered.
        // Atomic across both tables on the same shard.
        return tm.inTransaction(_ -> {
            final var events = eventEntityRepository.findUndelivered(tm.shardIdentifier, batchSize);
            if (events.isEmpty()) return 0;

            final var now = clock.instant();
            final var notifications = new ArrayList<EventNotification>(events.size());
            final var eventIds = new ArrayList<UUID>(events.size());
            for (var event : events) {
                eventIds.add(event.id);
                final var handlerNames = eventHandlerRegistry.subscribersFor(event.eventType);
                if (handlerNames.isEmpty()) continue;
                for (var handlerName : handlerNames) {
                    notifications.add(EventNotification.eventNotification()
                            .id(UUID.randomUUID())
                            .eventId(event.id)
                            .handlerName(handlerName)
                            // denormalized event + action context — copied from the event
                            // so dispatch never has to fetch the events table again.
                            .namespace(event.namespace)
                            .actionId(event.actionId)
                            .actionName(event.actionName)
                            .actionParams(event.actionParams)
                            .startedDate(event.startedDate)
                            .completionDate(event.completionDate)
                            .modelId(event.modelId)
                            .modelType(event.modelType)
                            .eventType(event.eventType)
                            .payload(event.payload)
                            .eventDate(event.eventDate)
                            // delivery state
                            .state(EventNotificationState.PENDING)
                            .attempts(0)
                            .nextRetryAt(event.eventDate)
                            .createdDate(now)
                            .updatedDate(now)
                            .build());
                }
            }

            eventNotificationRepository.addAllNoResult(notifications, tm.shardIdentifier);
            eventEntityRepository.markDelivered(eventIds, tm.shardIdentifier);
            EVENTS_FANNED_OUT.add(events.size());
            NOTIFICATIONS_CREATED.add(notifications.size());
            return events.size();
        });
    }

    private static boolean shouldStop(ExecutionContext ctx) {
        return ctx.getSchedulerState().isShuttingDown()
                || Thread.currentThread().isInterrupted();
    }

    public static Builder eventFanoutJob() {
        return new Builder();
    }

    public static final class Builder {
        private String name = DEFAULT_NAME;
        private Duration pollDelay = DEFAULT_POLL_DELAY;
        private int batchSize = DEFAULT_BATCH_SIZE;
        private DatabaseRegistry databaseRegistry;
        private EventHandlerRegistry eventHandlerRegistry;
        private Clock clock;

        private Builder() {}

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * The duration governs both how long {@code execute()} sleeps between rounds when a
         * round produced no work, and (by way of {@link FixedDelay}) how long db-scheduler
         * waits before re-running {@code execute()} if it ever returns. Default 1 second.
         */
        public Builder pollDelay(Duration pollDelay) {
            this.pollDelay = pollDelay;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder databaseRegistry(DatabaseRegistry databaseRegistry) {
            this.databaseRegistry = databaseRegistry;
            return this;
        }

        public Builder eventHandlerRegistry(EventHandlerRegistry eventHandlerRegistry) {
            this.eventHandlerRegistry = eventHandlerRegistry;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public EventFanoutJob build() {
            return new EventFanoutJob(this);
        }
    }
}
