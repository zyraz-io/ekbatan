package io.ekbatan.events.localeventhandler.job;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.distributedjobs.DistributedJob;
import io.ekbatan.events.localeventhandler.EventHandler;
import io.ekbatan.events.localeventhandler.EventHandlerRegistry;
import io.ekbatan.events.localeventhandler.model.EventNotification;
import io.ekbatan.events.localeventhandler.repository.EventNotificationRepository;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link DistributedJob} that drains every shard's due {@code event_notifications}. Each
 * notification row carries a denormalized copy of the event and action context (snapshotted
 * by the fan-out job at write time), so dispatch reads everything it needs from the single
 * row — no JOIN, no second query.
 *
 * <p>Behavior per claimed row:
 * <ol>
 *   <li><b>Pre-flight cap check</b>: if {@code now > event_date + retentionWindow} (default
 *       7 days), the row is transitioned to EXPIRED without invoking the handler.</li>
 *   <li><b>Resolve handler</b> via {@link EventHandlerRegistry#handlerFor(String)}. If no
 *       handler is currently registered for the row's name (e.g. it was removed in a recent
 *       deploy), the row is treated as a delivery failure and retried until the cap.</li>
 *   <li><b>Deserialize</b> the row's {@code payload} into the handler's
 *       {@link EventHandler#eventType()} via the configured {@link ObjectMapper}.</li>
 *   <li><b>Invoke</b> the handler. Success → SUCCEEDED. Throw → bump attempts, schedule
 *       next retry via {@link Backoff} capped at {@code maxBackoffCap}, or EXPIRED if the
 *       proposed retry would land past the deadline.</li>
 * </ol>
 *
 * <p>Within a single batch: pre-flight expiry rows are bucketed up-front; the remaining
 * rows have their handlers invoked in parallel on virtual threads. After every invocation
 * returns, the outcomes are bucketed (succeeded, retry, post-failure expired) and each
 * non-empty bucket is committed in a single batch UPDATE. Worst case: one UPDATE per bucket
 * plus one UPDATE per distinct {@code attempts} value among retries. Crash mid-batch is
 * safe under idempotent handlers — uncommitted rows are picked up on the next round.
 *
 * <p>Across shards: each {@code execute()} runs a continuous round-by-round loop where one
 * round drains a single batch from every shard in parallel on virtual threads, waits for
 * all shards to finish, then either starts another round (if any shard had work) or sleeps
 * for {@link Builder#pollDelay} before checking again. Only shutdown causes
 * {@code execute()} to return.
 */
public final class EventHandlingJob extends DistributedJob {

    private static final Logger LOG = LoggerFactory.getLogger(EventHandlingJob.class);
    private static final Meter METER = GlobalOpenTelemetry.get().getMeter("io.ekbatan.events.localeventhandler");
    private static final LongCounter HANDLED = METER.counterBuilder("ekbatan.events.handled")
            .setDescription("Notification rows processed by the event-handling job, tagged by outcome")
            .setUnit("{notification}")
            .build();
    private static final AttributeKey<String> OUTCOME_KEY = AttributeKey.stringKey("outcome");
    private static final Attributes OUTCOME_SUCCEEDED = Attributes.of(OUTCOME_KEY, "succeeded");
    private static final Attributes OUTCOME_FAILED_RETRY = Attributes.of(OUTCOME_KEY, "failed_retry");
    private static final Attributes OUTCOME_EXPIRED_PREFLIGHT = Attributes.of(OUTCOME_KEY, "expired_preflight");
    private static final Attributes OUTCOME_EXPIRED_POSTFAILURE = Attributes.of(OUTCOME_KEY, "expired_postfailure");

    private static final String DEFAULT_NAME = "ekbatan-event-handling";
    private static final Duration DEFAULT_POLL_DELAY = Duration.ofSeconds(1);
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final Duration DEFAULT_MAX_BACKOFF_CAP = Duration.ofMinutes(5);
    private static final Duration DEFAULT_RETENTION_WINDOW = Duration.ofDays(7);

    private final String name;
    private final Duration pollDelay;
    private final int batchSize;
    private final Duration maxBackoffCap;
    private final Duration retentionWindow;
    private final DatabaseRegistry databaseRegistry;
    private final EventHandlerRegistry eventHandlerRegistry;
    private final ObjectMapper objectMapper;
    private final EventNotificationRepository eventNotificationRepository;
    private final Clock clock;

    private EventHandlingJob(Builder builder) {
        this.name = Validate.notNull(builder.name, "name is required");
        this.pollDelay = Validate.notNull(builder.pollDelay, "pollDelay is required");
        Validate.isTrue(!this.pollDelay.isNegative() && !this.pollDelay.isZero(), "pollDelay must be positive");
        this.batchSize = builder.batchSize;
        Validate.isTrue(this.batchSize > 0, "batchSize must be positive");
        this.maxBackoffCap = Validate.notNull(builder.maxBackoffCap, "maxBackoffCap is required");
        Validate.isTrue(
                !this.maxBackoffCap.isNegative() && !this.maxBackoffCap.isZero(), "maxBackoffCap must be positive");
        this.retentionWindow = Validate.notNull(builder.retentionWindow, "retentionWindow is required");
        Validate.isTrue(
                !this.retentionWindow.isNegative() && !this.retentionWindow.isZero(),
                "retentionWindow must be positive");
        this.databaseRegistry = Validate.notNull(builder.databaseRegistry, "databaseRegistry is required");
        this.eventHandlerRegistry = Validate.notNull(builder.eventHandlerRegistry, "eventHandlerRegistry is required");
        this.objectMapper = Validate.notNull(builder.objectMapper, "objectMapper is required");
        this.clock = Validate.notNull(builder.clock, "clock is required");
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
        // (which equals our pollDelay). Per-handler exceptions are already absorbed in
        // classify() and turned into FAILED-state transitions, so this only fires on
        // genuinely unexpected per-round failures (DB connectivity etc.).
    }

    /**
     * One round: launch one virtual-thread fork per shard, each draining a single batch.
     * Wait for all forks. Returns whether any fork processed notifications.
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
                    throw new RuntimeException("Handling worker threw", e.getCause());
                }
            }
            return any;
        }
    }

    private int drainBatch(TransactionManager tm) throws InterruptedException {
        final var now = clock.instant();
        final List<EventNotification> notifications =
                eventNotificationRepository.findDue(tm.shardIdentifier, batchSize, now);
        if (notifications.isEmpty()) return 0;

        // Pre-flight bucket: rows whose deadline has already passed never get invoked.
        final var preflightExpiredIds = new ArrayList<UUID>();
        final var toInvoke = new ArrayList<EventNotification>();
        for (var n : notifications) {
            if (now.isAfter(n.eventDate.plus(retentionWindow))) {
                preflightExpiredIds.add(n.id);
            } else {
                toInvoke.add(n);
            }
        }

        // Parallel handler invocations; each fork returns the row plus a SUCCEEDED/FAILED tag.
        // EXPIRE-vs-RETRY for failures is decided post-invocation against postInvokeNow (below).
        final var succeededIds = new ArrayList<UUID>();
        final var failedNotifications = new ArrayList<EventNotification>();
        if (!toInvoke.isEmpty()) {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var futures = toInvoke.stream()
                        .map(n -> executor.submit(() -> classify(n)))
                        .toList();
                for (var f : futures) {
                    try {
                        final var outcome = f.get();
                        if (outcome.kind == Outcome.Kind.SUCCEEDED) {
                            succeededIds.add(outcome.notification.id);
                        } else {
                            failedNotifications.add(outcome.notification);
                        }
                    } catch (ExecutionException e) {
                        throw new RuntimeException("Handling worker threw", e.getCause());
                    }
                }
            }
        }

        // Capture the timestamp *after* all invocations complete. Used for both the
        // EXPIRE-vs-RETRY decision and the actual UPDATE values, so that next_retry_at is
        // genuinely in the future relative to wall-clock time even when handlers ran longer
        // than Backoff(...). Otherwise we'd thrash: re-fetching the same rows immediately
        // because their next_retry_at landed in the past while we were invoking handlers.
        final var postInvokeNow = clock.instant();

        // Bucket failures into RETRY (per existing attempts, so each bucket shares one
        // resolved next_retry_at) vs EXPIRE (proposed retry would land past the deadline).
        final var failedExpiredIds = new ArrayList<UUID>();
        final var failedRetryByAttempts = new HashMap<Integer, List<UUID>>();
        for (var n : failedNotifications) {
            final var deadline = n.eventDate.plus(retentionWindow);
            final var proposedNextRetry = postInvokeNow.plus(Backoff.delay(n.attempts + 1, maxBackoffCap));
            if (proposedNextRetry.isAfter(deadline)) {
                failedExpiredIds.add(n.id);
            } else {
                failedRetryByAttempts
                        .computeIfAbsent(n.attempts, _ -> new ArrayList<>())
                        .add(n.id);
            }
        }

        // One batch UPDATE per non-empty bucket. Repository methods short-circuit on empty.
        eventNotificationRepository.markExpiredAllPreflight(preflightExpiredIds, postInvokeNow, tm.shardIdentifier);
        eventNotificationRepository.markSucceededAll(succeededIds, postInvokeNow, tm.shardIdentifier);
        eventNotificationRepository.markExpiredAllPostFailure(failedExpiredIds, postInvokeNow, tm.shardIdentifier);
        int failedRetryCount = 0;
        for (var entry : failedRetryByAttempts.entrySet()) {
            final var newAttempts = entry.getKey() + 1;
            final var nextRetry = postInvokeNow.plus(Backoff.delay(newAttempts, maxBackoffCap));
            eventNotificationRepository.markFailedBucket(
                    entry.getValue(), nextRetry, postInvokeNow, tm.shardIdentifier);
            failedRetryCount += entry.getValue().size();
        }

        if (!preflightExpiredIds.isEmpty()) HANDLED.add(preflightExpiredIds.size(), OUTCOME_EXPIRED_PREFLIGHT);
        if (!succeededIds.isEmpty()) HANDLED.add(succeededIds.size(), OUTCOME_SUCCEEDED);
        if (!failedExpiredIds.isEmpty()) HANDLED.add(failedExpiredIds.size(), OUTCOME_EXPIRED_POSTFAILURE);
        if (failedRetryCount > 0) HANDLED.add(failedRetryCount, OUTCOME_FAILED_RETRY);

        return notifications.size();
    }

    private Outcome classify(EventNotification n) {
        try {
            invoke(n);
            return new Outcome(n, Outcome.Kind.SUCCEEDED);
        } catch (InterruptedException ie) {
            // Preserve interrupt status so the worker's continuation observes shutdown.
            Thread.currentThread().interrupt();
            LOG.warn("Handler '{}' interrupted while processing event {}", n.handlerName, n.eventId);
            return new Outcome(n, Outcome.Kind.FAILED);
        } catch (Exception e) {
            LOG.warn(
                    "Handler '{}' threw {} while processing event {} (attempts={}): {}",
                    n.handlerName,
                    e.getClass().getSimpleName(),
                    n.eventId,
                    n.attempts,
                    e.getMessage(),
                    e);
            return new Outcome(n, Outcome.Kind.FAILED);
        }
    }

    private record Outcome(EventNotification notification, Kind kind) {
        enum Kind {
            SUCCEEDED,
            FAILED
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void invoke(EventNotification notification) throws Exception {
        final EventHandler<?> handler = eventHandlerRegistry.handlerFor(notification.handlerName);
        if (handler == null) {
            throw new IllegalStateException("No handler registered with name: " + notification.handlerName + " (event "
                    + notification.eventId + ")");
        }
        final var typedEvent = objectMapper.treeToValue(notification.payload, handler.eventType());
        ((EventHandler) handler).handle(typedEvent);
    }

    private static boolean shouldStop(ExecutionContext ctx) {
        return ctx.getSchedulerState().isShuttingDown()
                || Thread.currentThread().isInterrupted();
    }

    public static Builder eventHandlingJob() {
        return new Builder();
    }

    public static final class Builder {
        private String name = DEFAULT_NAME;
        private Duration pollDelay = DEFAULT_POLL_DELAY;
        private int batchSize = DEFAULT_BATCH_SIZE;
        private Duration maxBackoffCap = DEFAULT_MAX_BACKOFF_CAP;
        private Duration retentionWindow = DEFAULT_RETENTION_WINDOW;
        private DatabaseRegistry databaseRegistry;
        private EventHandlerRegistry eventHandlerRegistry;
        private ObjectMapper objectMapper;
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

        /**
         * Upper bound on the exponential-backoff retry delay. The curve doubles from 30s and
         * is capped at this value. Default: 5 minutes.
         */
        public Builder maxBackoffCap(Duration maxBackoffCap) {
            this.maxBackoffCap = maxBackoffCap;
            return this;
        }

        /**
         * How long after {@code event_date} the dispatch job will keep retrying a row before
         * giving up and transitioning it to EXPIRED. Default: 7 days.
         */
        public Builder retentionWindow(Duration retentionWindow) {
            this.retentionWindow = retentionWindow;
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

        /**
         * Used by the dispatch job to deserialize the notification's denormalized
         * {@code payload} into the handler's typed event class. The same mapper used by the
         * persister at write time should normally be passed here so round-tripping just
         * works.
         */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public EventHandlingJob build() {
            return new EventHandlingJob(this);
        }
    }
}
