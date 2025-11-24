package io.ekbatan.core.persistence;

import io.ekbatan.core.persistence.connection.ConnectionProvider;
import io.ekbatan.core.persistence.connection.TransactionConnectionWrapper;
import java.sql.Connection;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.commons.lang3.Validate;
import org.jooq.DSLContext;

public class TransactionManager {

    public final ConnectionProvider primaryConnectionProvider;
    public final ConnectionProvider secondaryConnectionProvider;

    private final ScopedValue<TransactionConnectionWrapper> currentTransaction;

    public TransactionManager(
            ConnectionProvider primaryConnectionProvider, ConnectionProvider secondaryConnectionProvider) {
        this.primaryConnectionProvider =
                Validate.notNull(primaryConnectionProvider, "primaryConnectionProvider should not be null");
        this.secondaryConnectionProvider =
                Validate.notNull(secondaryConnectionProvider, "secondaryConnectionProvider should not be null");
        this.currentTransaction = ScopedValue.newInstance();
    }

    public <R> R inTransactionChecked(CheckedFunction<DSLContext, R> block) throws Exception {
        Connection newConn = null;
        try {
            newConn = primaryConnectionProvider.acquire();
            final var wrapper = new TransactionConnectionWrapper(newConn);
            return ScopedValue.where(currentTransaction, wrapper).call(() -> {
                try {
                    wrapper.begin();
                    final var result = block.apply(wrapper.dslContext());
                    wrapper.commit();
                    return result;
                } catch (Exception e) {
                    wrapper.rollback();
                    throw e;
                }
            });
        } finally {
            if (newConn != null) {
                primaryConnectionProvider.release(newConn);
            }
        }
    }

    public void inTransactionChecked(CheckedConsumer<DSLContext> block) throws Exception {
        inTransactionChecked(dslContext -> {
            block.accept(dslContext);
            return null;
        });
    }

    public <R> R inTransaction(Function<DSLContext, R> block) {
        try {
            return inTransactionChecked(block::apply);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void inTransaction(Consumer<DSLContext> block) {
        try {
            inTransactionChecked(block::accept);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<DSLContext> currentTransactionDbContext() {
        return currentTransaction.isBound()
                ? Optional.of(currentTransaction.get().dslContext())
                : Optional.empty();
    }

    @FunctionalInterface
    public interface CheckedConsumer<T> {
        void accept(T t) throws Exception;
    }

    @FunctionalInterface
    public interface CheckedFunction<T, R> {
        R apply(T t) throws Exception;
    }
}
