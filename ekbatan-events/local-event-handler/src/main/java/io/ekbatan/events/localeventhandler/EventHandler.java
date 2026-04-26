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
     */
    String name();

    /** The {@link ModelEvent} subtype this handler subscribes to. */
    Class<E> eventType();

    /**
     * Handle one delivery. Throwing causes the dispatch job to record a failure and
     * schedule a retry, subject to the {@code maxBackoffCap} backoff and the
     * {@code retentionWindow} cap (after which the row is transitioned to {@code EXPIRED}
     * and never invoked again).
     */
    void handle(E event) throws Exception;
}
