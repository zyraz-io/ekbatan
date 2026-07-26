package io.ekbatan.core.concurrent;

/**
 * Thrown when a {@link KeyedLockProvider} cannot complete an acquisition because the backend
 * failed - a SQL error, a terminated connection, a server-side error response.
 *
 * <p>This signals a <em>failure</em>, never contention. A lock that is simply held by somebody
 * else is reported by {@link KeyedLockProvider#tryAcquire} returning
 * {@link java.util.Optional#empty()}, and by {@link KeyedLockProvider#acquire} continuing to
 * wait - neither raises this exception.
 *
 * <p>When the provider's own cleanup (returning the borrowed connection to the pool) also fails
 * while handling the original backend error, that secondary failure is attached via
 * {@link Throwable#addSuppressed(Throwable)} so it can never mask the cause the caller needs.
 */
public class LockAcquisitionException extends RuntimeException {

    /** The lock key whose acquisition failed. */
    public final String key;

    /**
     * Constructs the exception for a failure with an underlying cause.
     *
     * @param key the lock key whose acquisition failed.
     * @param detail short description of what failed, appended to the message.
     * @param cause the underlying backend failure.
     */
    public LockAcquisitionException(String key, String detail, Throwable cause) {
        super("Failed to acquire lock for key " + key + ": " + detail, cause);
        this.key = key;
    }

    /**
     * Constructs the exception for a failure with no underlying exception - for example a server
     * that answered the lock request with a NULL result.
     *
     * @param key the lock key whose acquisition failed.
     * @param detail short description of what failed, appended to the message.
     */
    public LockAcquisitionException(String key, String detail) {
        super("Failed to acquire lock for key " + key + ": " + detail);
        this.key = key;
    }
}
