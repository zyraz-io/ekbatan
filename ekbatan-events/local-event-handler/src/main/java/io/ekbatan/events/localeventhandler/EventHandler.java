package io.ekbatan.events.localeventhandler;

import io.ekbatan.core.domain.ModelEvent;

/**
 * The contract a user implements to react to a specific {@link ModelEvent} subtype.
 *
 * <p>An application registers one or more {@code EventHandler}s with an
 * {@link EventHandlerRegistry}, then wires the registry into both {@code EventFanoutJob}
 * (which uses it to learn subscriptions) and {@code EventHandlingJob} (which uses it to
 * route deliveries to the named handler).
 *
 * <p>Multiple handlers may subscribe to the same event type. Each gets its own
 * {@code event_notifications} row and its own retry/expiry lifecycle.
 *
 * @param <E> the {@link ModelEvent} subtype this handler subscribes to
 */
public interface EventHandler<E extends ModelEvent<?>> {

    /**
     * Cluster-stable identifier for this handler. Stored in
     * {@code event_notifications.handler_name}; renaming a deployed handler effectively
     * makes it a new handler (existing rows for the old name will eventually expire).
     *
     * @return the handler's stable name.
     */
    String name();

    /** {@return the {@link ModelEvent} subtype this handler subscribes to} */
    Class<E> eventType();

    /**
     * Handle one delivery. The {@link EventEnvelope} carries the typed event payload plus
     * the surrounding action context (namespace, action id/name/params, timestamps,
     * model id/type, event date, event id). Throwing causes the dispatch job to record a
     * failure and schedule a retry, subject to the {@code maxBackoffCap} backoff and the
     * {@code retentionWindow} cap (after which the row is transitioned to {@code EXPIRED}
     * and never invoked again).
     *
     * @param envelope the delivery's typed payload plus its surrounding action context.
     * @throws Exception any exception is treated as a failed delivery and triggers a retry.
     */
    void handle(EventEnvelope<E> envelope) throws Exception;
}
