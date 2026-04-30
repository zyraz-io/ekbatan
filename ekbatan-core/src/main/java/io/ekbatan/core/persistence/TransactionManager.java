package io.ekbatan.core.persistence;

import io.ekbatan.core.shard.ShardIdentifier;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.sql.Connection;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.commons.lang3.Validate;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransactionManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionManager.class);
    private static final Tracer TRACER = GlobalOpenTelemetry.get().getTracer("io.ekbatan.core", "1.0.0");

    public final SQLDialect dialect;
    public final ShardIdentifier shardIdentifier;

    public final ConnectionProvider primaryConnectionProvider;
    public final ConnectionProvider secondaryConnectionProvider;

    private final ScopedValue<Transaction> currentTransaction;

    public TransactionManager(
            ConnectionProvider primaryConnectionProvider,
            ConnectionProvider secondaryConnectionProvider,
            SQLDialect dialect) {
        this(primaryConnectionProvider, secondaryConnectionProvider, dialect, ShardIdentifier.DEFAULT);
    }

    public TransactionManager(
            ConnectionProvider primaryConnectionProvider,
            ConnectionProvider secondaryConnectionProvider,
            SQLDialect dialect,
            ShardIdentifier shardIdentifier) {
        this.dialect = Validate.notNull(dialect, "dialect should not be null");
        this.primaryConnectionProvider =
                Validate.notNull(primaryConnectionProvider, "primaryConnectionProvider should not be null");
        this.secondaryConnectionProvider =
                Validate.notNull(secondaryConnectionProvider, "secondaryConnectionProvider should not be null");
        this.shardIdentifier = Validate.notNull(shardIdentifier, "shardIdentifier should not be null");
        this.currentTransaction = ScopedValue.newInstance();
    }

    public <R> R inTransactionChecked(CheckedFunction<DSLContext, R> block) throws Exception {
        final var span = TRACER.spanBuilder("ekbatan.transaction")
                .setAttribute("ekbatan.shard.group", shardIdentifier.group)
                .setAttribute("ekbatan.shard.member", shardIdentifier.member)
                .startSpan();
        try (var _ = span.makeCurrent()) {
            return executeInTransaction(block, span);
        } finally {
            span.end();
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

    private <R> R executeInTransaction(CheckedFunction<DSLContext, R> block, Span span) throws Exception {
        Connection newConn = null;
        try {
            newConn = primaryConnectionProvider.acquire();
            final var wrapper = new Transaction(newConn, dialect);
            return ScopedValue.where(currentTransaction, wrapper).call(() -> {
                try {
                    wrapper.begin();
                    LOG.debug("Transaction started [shard=({},{})]", shardIdentifier.group, shardIdentifier.member);
                    final var result = block.apply(wrapper.dslContext());
                    wrapper.commit();
                    LOG.debug("Transaction committed [shard=({},{})]", shardIdentifier.group, shardIdentifier.member);
                    return result;
                } catch (Exception e) {
                    wrapper.rollback();
                    LOG.warn(
                            "Transaction rolled back [shard=({},{})]: {}",
                            shardIdentifier.group,
                            shardIdentifier.member,
                            e.getClass().getSimpleName());
                    span.setStatus(StatusCode.ERROR, e.getMessage());
                    span.recordException(e);
                    throw e;
                }
            });
        } finally {
            if (newConn != null) {
                primaryConnectionProvider.release(newConn);
            }
        }
    }

    public Optional<DSLContext> currentTransactionDbContext() {
        return currentTransaction.isBound()
                ? Optional.of(currentTransaction.get().dslContext())
                : Optional.empty();
    }

    /**
     * Closes the primary and (distinct) secondary connection providers. Safe to call multiple
     * times — Hikari's {@code close()} is idempotent.
     */
    @Override
    public void close() {
        primaryConnectionProvider.close();
        if (secondaryConnectionProvider != primaryConnectionProvider) {
            secondaryConnectionProvider.close();
        }
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
