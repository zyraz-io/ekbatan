package io.ekbatan.core.persistence;

import java.util.function.Function;

/**
 * Manages transactions for a specific connection type.
 *
 * @param <C> the connection type
 */
public interface TransactionManager<C> {
    /**
     * Executes the given action within a transaction.
     *
     * @param action the action to execute within a transaction
     * @param <T> the return type
     * @return the result of the action
     * @throws TransactionException if an error occurs during transaction management
     */
    <T> T withTransaction(Function<C, T> action) throws TransactionException;

    /**
     * Executes the given action within a transaction with the specified mode.
     *
     * @param mode the transaction mode (e.g., REQUIRE_NEW, REQUIRE_EXISTING)
     * @param action the action to execute within a transaction
     * @param <T> the return type
     * @return the result of the action
     * @throws TransactionException if an error occurs during transaction management
     * @throws IllegalStateException if the requested transaction mode cannot be satisfied
     */
    <T> T withTransaction(ConnectionMode mode, Function<C, T> action)
            throws TransactionException, IllegalStateException;

    /**
     * Exception thrown when a transaction-related error occurs.
     */
    class TransactionException extends RuntimeException {
        public TransactionException(String message) {
            super(message);
        }

        public TransactionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
