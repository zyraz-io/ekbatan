package io.ekbatan.events.localeventhandler.model;

/**
 * State of an {@code event_notifications} row through its lifecycle.
 *
 * <p>Transitions are owned exclusively by the dispatch job:
 * <ul>
 *   <li>{@link #PENDING} -> {@link #SUCCEEDED} on first-attempt success
 *   <li>{@link #PENDING} -> {@link #FAILED} on first-attempt failure (retry scheduled)
 *   <li>{@link #FAILED} -> {@link #SUCCEEDED} on a later retry succeeding
 *   <li>{@link #FAILED} -> {@link #FAILED} on continued failure within the cap (retry rescheduled)
 *   <li>{@link #PENDING} or {@link #FAILED} -> {@link #EXPIRED} when the 7-day cap is reached
 * </ul>
 *
 * <p>{@link #SUCCEEDED} and {@link #EXPIRED} are terminal - rows leave the dispatch index and
 * are never picked up again.
 */
public enum EventNotificationState {
    /** Initial state; row is awaiting its first delivery attempt. */
    PENDING,

    /** Terminal; the handler returned normally. */
    SUCCEEDED,

    /** Transient; the handler threw and the row is scheduled for retry within the cap. */
    FAILED,

    /** Terminal; retention window elapsed without success. */
    EXPIRED
}
