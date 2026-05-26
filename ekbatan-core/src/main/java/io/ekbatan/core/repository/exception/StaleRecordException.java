package io.ekbatan.core.repository.exception;

/**
 * Thrown when an optimistic-locked {@code UPDATE} affects zero rows - the version-in-DB
 * differed from the {@code version} attached to the {@code Persistable} being saved, meaning
 * some concurrent writer modified the row between read and write.
 *
 * <p>The default {@link io.ekbatan.core.action.ExecutionConfiguration} retries this once
 * after 100ms; persistent staleness usually indicates contention worth investigating rather
 * than a transient race.
 */
public class StaleRecordException extends RuntimeException {

    /** Constructs the exception with no message or cause. */
    public StaleRecordException() {
        super();
    }

    /**
     * Constructs the exception with a diagnostic message.
     *
     * @param message the diagnostic message (typically the class + id of the stale row).
     */
    public StaleRecordException(String message) {
        super(message);
    }

    /**
     * Constructs the exception with a diagnostic message and a wrapped cause.
     *
     * @param message the diagnostic message.
     * @param cause the underlying cause.
     */
    public StaleRecordException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs the exception wrapping an underlying cause.
     *
     * @param cause the underlying cause.
     */
    public StaleRecordException(Throwable cause) {
        super(cause);
    }
}
