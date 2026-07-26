package io.ekbatan.events.localeventhandler.job;

import static io.ekbatan.events.localeventhandler.EventEnvelope.Builder.eventEnvelope;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import io.ekbatan.core.internal.Validate;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.distributedjobs.DistributedJob;
import io.ekbatan.events.localeventhandler.EventEnvelope;
import io.ekbatan.events.localeventhandler.EventHandler;
import io.ekbatan.events.localeventhandler.EventHandlerRegistry;
import io.ekbatan.events.localeventhandler.model.EventNotification;
import io.ekbatan.events.localeventhandler.repository.EventNotificationRepository;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link DistributedJob} that drains every shard's due {@code event_notifications}. Each
 * notification row carries a denormalized copy of the event and action context (snapshotted
 * by the fan-out job at write time), so dispatch reads everything it needs from the single
 * row - no JOIN, no second query.
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
 *   <li><b>Invoke</b> the handler. Success -> SUCCEEDED. Throw -> bump attempts, schedule
 *       next retry via {@link Backoff} capped at {@code maxBackoffCap}, or EXPIRED if the
 *       proposed retry would land past the deadline.</li>
 * </ol>
 *
 * <p>Within a single batch: pre-flight expiry rows are bucketed up-front; the remaining
 * rows have their handlers invoked in parallel on virtual threads. After every invocation
 * returns, the outcomes are bucketed (succeeded, retry, post-failure expired) and each
 * non-empty bucket is committed in a single batch UPDATE. Worst case: one UPDATE per bucket
 * plus one UPDATE per distinct {@code attempts} value among retries. Crash mid-batch is
 * safe under idempotent handlers - uncommitted rows are picked up on the next round.
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
    /**
     * Wall-clock duration of a single handler invocation, tagged by handler and by whether that
     * invocation succeeded. Answers "which handler is slow", which no counter can - a handler that
     * degrades from 5ms to 5s shows up here long before it starts failing.
     */
    private static final DoubleHistogram HANDLER_DURATION = METER.histogramBuilder("ekbatan.events.handler.duration")
            .setDescription("Wall-clock duration of a single event-handler invocation")
            .setUnit("s")
            .build();

    /**
     * End-to-end delivery lag: how old the source event was when its handler finally succeeded,
     * tagged by handler. Recorded on success only, so it measures time-to-delivery rather than
     * time-spent-failing. This is the signal that reveals a growing backlog - counters cannot,
     * because a shard falling behind still reports a healthy success count.
     */
    private static final DoubleHistogram DELIVERY_LAG = METER.histogramBuilder("ekbatan.events.delivery.lag")
            .setDescription("Age of the source event when its handler succeeded")
            .setUnit("s")
            .build();

    private static final AttributeKey<String> HANDLER_KEY = AttributeKey.stringKey("handler");
    private static final AttributeKey<String> OUTCOME_KEY = AttributeKey.stringKey("outcome");
    private static final String OUTCOME_SUCCEEDED = "succeeded";
    private static final String OUTCOME_FAILED_RETRY = "failed_retry";
    private static final String OUTCOME_EXPIRED_PREFLIGHT = "expired_preflight";
    private static final String OUTCOME_EXPIRED_POSTFAILURE = "expired_postfailure";

    /**
     * {@link #HANDLER_DURATION} reports a binary {@code outcome} of {@code succeeded} / {@code
     * failed}, deliberately narrower than the four values above: at the moment a handler returns,
     * the only fact known is whether it threw. Whether a failure becomes a retry or an expiry is
     * decided later, against the post-invocation clock, and a pre-flight expiry never invokes a
     * handler at all - so it can never appear in the duration histogram.
     */
    private static final String OUTCOME_FAILED = "failed";

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

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
            // graceful return - db-scheduler is interrupting us during shutdown
        }
        // RuntimeException propagates: db-scheduler re-invokes execute() after FixedDelay
        // (which equals our pollDelay). Per-handler exceptions are already absorbed in
        // classify() and turned into FAILED-state transitions, so this only fires on
        // genuinely unexpected per-round failures (DB connectivity etc.).
    }

    /**
     * One round: launch one virtual-thread fork per shard, each draining a single batch.
     * Wait for all forks.
     *
     * @return {@code true} if any shard processed at least one notification in this round.
     * @throws InterruptedException if the calling thread is interrupted while waiting for forks.
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
        final var preflightExpired = new ArrayList<EventNotification>();
        final var toInvoke = new ArrayList<EventNotification>();
        for (var n : notifications) {
            if (now.isAfter(n.eventDate.plus(retentionWindow))) {
                preflightExpired.add(n);
            } else {
                toInvoke.add(n);
            }
        }

        // Parallel handler invocations; each fork returns the row plus a SUCCEEDED/FAILED tag.
        // EXPIRE-vs-RETRY for failures is decided post-invocation against postInvokeNow (below).
        // Notifications rather than bare ids: DELIVERY_LAG needs each row's eventDate and handler.
        final var succeeded = new ArrayList<EventNotification>();
        final var failedNotifications = new ArrayList<EventNotification>();
        if (!toInvoke.isEmpty()) {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var futures = toInvoke.stream()
                        .map(n -> executor.submit(() -> classify(n)))
                        .toList();
                // Indexed rather than for-each so a fork that died without producing an Outcome can
                // still be attributed to its notification.
                for (int i = 0; i < futures.size(); i++) {
                    final var n = toInvoke.get(i);
                    try {
                        final var outcome = futures.get(i).get();
                        if (outcome.kind == Outcome.Kind.SUCCEEDED) {
                            succeeded.add(outcome.notification);
                        } else {
                            failedNotifications.add(outcome.notification);
                        }
                    } catch (ExecutionException e) {
                        // The fork died outside classify()'s reach. This is NOT swallowing the
                        // throwable - FutureTask.run() already caught it one frame below and stored
                        // it as this future's outcome; all we do is decide what it means for this
                        // row. Rethrowing here instead would abort the loop before the state UPDATEs
                        // below, discarding the decisions already made for every healthy sibling in
                        // the batch - which, since their handlers have already run, means they are
                        // re-invoked successfully once per poll forever.
                        LOG.error(
                                "Handler '{}' died while processing event {} (attempts={}); recording FAILED",
                                n.handlerName,
                                n.eventId,
                                n.attempts,
                                e.getCause());
                        failedNotifications.add(n);
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
        final var failedExpired = new ArrayList<EventNotification>();
        final var failedRetryByAttempts = new HashMap<Integer, List<EventNotification>>();
        for (var n : failedNotifications) {
            final var deadline = n.eventDate.plus(retentionWindow);
            final var proposedNextRetry = postInvokeNow.plus(Backoff.delay(n.attempts + 1, maxBackoffCap));
            if (proposedNextRetry.isAfter(deadline)) {
                failedExpired.add(n);
            } else {
                failedRetryByAttempts
                        .computeIfAbsent(n.attempts, _ -> new ArrayList<>())
                        .add(n);
            }
        }

        // One batch UPDATE per non-empty bucket. Repository methods short-circuit on empty.
        eventNotificationRepository.markExpiredAllPreflight(idsOf(preflightExpired), postInvokeNow, tm.shardIdentifier);
        eventNotificationRepository.markSucceededAll(idsOf(succeeded), postInvokeNow, tm.shardIdentifier);
        eventNotificationRepository.markExpiredAllPostFailure(idsOf(failedExpired), postInvokeNow, tm.shardIdentifier);
        for (var entry : failedRetryByAttempts.entrySet()) {
            final var newAttempts = entry.getKey() + 1;
            final var nextRetry = postInvokeNow.plus(Backoff.delay(newAttempts, maxBackoffCap));
            eventNotificationRepository.markFailedBucket(
                    idsOf(entry.getValue()), nextRetry, postInvokeNow, tm.shardIdentifier);
        }

        recordHandled(preflightExpired, OUTCOME_EXPIRED_PREFLIGHT);
        recordHandled(succeeded, OUTCOME_SUCCEEDED);
        recordHandled(failedExpired, OUTCOME_EXPIRED_POSTFAILURE);
        failedRetryByAttempts.values().forEach(rows -> recordHandled(rows, OUTCOME_FAILED_RETRY));

        for (var n : succeeded) {
            DELIVERY_LAG.record(
                    Duration.between(n.eventDate, postInvokeNow).toNanos() / NANOS_PER_SECOND,
                    Attributes.of(HANDLER_KEY, n.handlerName));
        }

        return notifications.size();
    }

    private static List<UUID> idsOf(List<EventNotification> notifications) {
        return notifications.stream().map(n -> n.id).toList();
    }

    /**
     * Emits {@link #HANDLED} for one outcome bucket, broken down by handler. Grouped rather than
     * incremented per row so a 100-row batch costs one counter add per distinct handler, not one
     * per notification.
     */
    private static void recordHandled(List<EventNotification> rows, String outcome) {
        if (rows.isEmpty()) {
            return;
        }
        final var countsByHandler = new HashMap<String, Long>();
        for (var n : rows) {
            countsByHandler.merge(n.handlerName, 1L, Long::sum);
        }
        countsByHandler.forEach(
                (handler, count) -> HANDLED.add(count, Attributes.of(OUTCOME_KEY, outcome, HANDLER_KEY, handler)));
    }

    private Outcome classify(EventNotification n) {
        // System.nanoTime, not the injected Clock: this is an elapsed-time measurement, and the
        // Clock exists for business timestamps (and is a VirtualClock in tests).
        final var startNanos = System.nanoTime();
        var kind = Outcome.Kind.FAILED;
        try {
            final var outcome = classifyInternal(n);
            kind = outcome.kind;
            return outcome;
        } finally {
            HANDLER_DURATION.record(
                    (System.nanoTime() - startNanos) / NANOS_PER_SECOND,
                    Attributes.of(
                            HANDLER_KEY,
                            n.handlerName,
                            OUTCOME_KEY,
                            kind == Outcome.Kind.SUCCEEDED ? OUTCOME_SUCCEEDED : OUTCOME_FAILED));
        }
    }

    private Outcome classifyInternal(EventNotification n) {
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
        } catch (Throwable t) {
            // An Error from user handler code is a failure of ONE notification, not of the batch or
            // the shard. Left uncaught it still reaches the collection loop, which records the same
            // FAILED for the same row - this arm exists so the log line can name the handler, the
            // event and the attempt count instead of being a generic post-mortem.
            //
            // Deliberately NOT guarded by a Reactor/RxJava-style throwIfFatal: their fatal sets are
            // {VirtualMachineError, ThreadDeath, LinkageError}, and NoClassDefFoundError IS a
            // LinkageError - i.e. precisely the case this catch exists to absorb. Rethrowing a
            // "fatal" subset here would also be inert, because db-scheduler catches Throwable in the
            // frame that calls execute() (ExecutePicked) and reschedules regardless. Compare
            // Jackson's ExceptionUtil.isFatal, which treats a corrupt class as fatal but a merely
            // missing one as a recoverable per-item failure.
            LOG.error(
                    "Handler '{}' threw Error {} while processing event {} (attempts={}): {}",
                    n.handlerName,
                    t.getClass().getSimpleName(),
                    n.eventId,
                    n.attempts,
                    t.getMessage(),
                    t);
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
        final EventEnvelope envelope = eventEnvelope()
                .event(typedEvent)
                .eventId(notification.eventId)
                .namespace(notification.namespace)
                .actionId(notification.actionId)
                .actionName(notification.actionName)
                .actionParams(notification.actionParams)
                .startedDate(notification.startedDate)
                .completionDate(notification.completionDate)
                .modelId(notification.modelId)
                .modelType(notification.modelType)
                .eventDate(notification.eventDate)
                .build();
        handler.handle(envelope);
    }

    private static boolean shouldStop(ExecutionContext ctx) {
        return ctx.getSchedulerState().isShuttingDown()
                || Thread.currentThread().isInterrupted();
    }

    /** {@return a fresh builder for {@link EventHandlingJob}} */
    public static Builder eventHandlingJob() {
        return new Builder();
    }

    /** Fluent builder for {@link EventHandlingJob}. Obtain via {@link #eventHandlingJob()}. */
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

        /**
         * Sets the db-scheduler task name.
         *
         * @param name db-scheduler task name (must be cluster-unique).
         * @return this builder, for chaining.
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * The duration governs both how long {@code execute()} sleeps between rounds when a
         * round produced no work, and (by way of {@link FixedDelay}) how long db-scheduler
         * waits before re-running {@code execute()} if it ever returns. Default 1 second.
         *
         * @param pollDelay the polling delay.
         * @return this builder, for chaining.
         */
        public Builder pollDelay(Duration pollDelay) {
            this.pollDelay = pollDelay;
            return this;
        }

        /**
         * Sets the per-round batch size.
         *
         * @param batchSize max notifications read per shard per round.
         * @return this builder, for chaining.
         */
        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        /**
         * Upper bound on the exponential-backoff retry delay. The curve doubles from 30s and
         * is capped at this value. Default: 5 minutes.
         *
         * @param maxBackoffCap the cap on retry delay.
         * @return this builder, for chaining.
         */
        public Builder maxBackoffCap(Duration maxBackoffCap) {
            this.maxBackoffCap = maxBackoffCap;
            return this;
        }

        /**
         * How long after {@code event_date} the dispatch job will keep retrying a row before
         * giving up and transitioning it to EXPIRED. Default: 7 days.
         *
         * @param retentionWindow the retention window.
         * @return this builder, for chaining.
         */
        public Builder retentionWindow(Duration retentionWindow) {
            this.retentionWindow = retentionWindow;
            return this;
        }

        /**
         * Sets the database registry.
         *
         * @param databaseRegistry the per-shard connection pools / transaction managers.
         * @return this builder, for chaining.
         */
        public Builder databaseRegistry(DatabaseRegistry databaseRegistry) {
            this.databaseRegistry = databaseRegistry;
            return this;
        }

        /**
         * Sets the event handler registry.
         *
         * @param eventHandlerRegistry the registry of handlers used to dispatch notifications.
         * @return this builder, for chaining.
         */
        public Builder eventHandlerRegistry(EventHandlerRegistry eventHandlerRegistry) {
            this.eventHandlerRegistry = eventHandlerRegistry;
            return this;
        }

        /**
         * Used by the dispatch job to deserialize the notification's denormalized
         * {@code payload} into the handler's typed event class. The same mapper used by the
         * persister at write time should normally be passed here so round-tripping just
         * works.
         *
         * @param objectMapper the Jackson mapper.
         * @return this builder, for chaining.
         */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        /**
         * Sets the clock.
         *
         * @param clock the system clock used for retention/backoff timestamps.
         * @return this builder, for chaining.
         */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /** {@return a configured {@link EventHandlingJob}} */
        public EventHandlingJob build() {
            return new EventHandlingJob(this);
        }
    }
}
