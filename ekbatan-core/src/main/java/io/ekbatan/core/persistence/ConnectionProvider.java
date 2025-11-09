package io.ekbatan.core.persistence;

/**
 * Provider for database connections.
 *
 * @param <C> The connection type
 */
public interface ConnectionProvider<C> {
    /**
     * Acquires a connection from the pool.
     *
     * @return a connection from the pool
     * @throws ConnectionException if a connection could not be obtained
     */
    C acquire() throws ConnectionException;

    /**
     * Releases a connection back to the pool.
     *
     * @param connection the connection to release
     */
    void release(C connection);

    /**
     * Exception thrown when a connection cannot be acquired.
     */
    class ConnectionException extends RuntimeException {
        public ConnectionException(String message) {
            super(message);
        }

        public ConnectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
