package io.ekbatan.core.persistence;

import io.ekbatan.core.persistence.connection.ConnectionMode;
import io.ekbatan.core.persistence.connection.ConnectionProvider;
import io.ekbatan.core.persistence.connection.TransactionConnectionWrapper;
import java.sql.Connection;
import org.apache.commons.lang3.Validate;

public class TransactionManager {

    private final ConnectionProvider primaryProvider;
    private final ConnectionProvider replicaProvider;

    private static final ScopedValue<TransactionConnectionWrapper> CURRENT = ScopedValue.newInstance();

    public TransactionManager(ConnectionProvider primaryProvider, ConnectionProvider replicaProvider) {
        this.primaryProvider = Validate.notNull(primaryProvider, "primaryProvider should not be null");
        this.replicaProvider = Validate.notNull(replicaProvider, "replicaProvider should not be null");
    }

    public <R> R withConnection(boolean primary, CheckedFunction<Connection, R> block) {
        return switch (currentConnection()) {
            case Connection existing -> block.apply(existing);
            case null -> {
                final var provider = providerOf(primary);
                final var conn = provider.acquire();
                try {
                    yield block.apply(conn);
                } finally {
                    provider.release(conn);
                }
            }
        };
    }

    public <R> R inTransaction(ConnectionMode mode, CheckedFunction<Connection, R> block) {
        return switch (currentConnection()) {
            case Connection existingConn -> {
                Validate.isTrue(
                        mode == ConnectionMode.REQUIRE_EXISTING,
                        "Required new connection but existing connection was found");
                yield block.apply(existingConn);
            }
            case null -> {
                Validate.isTrue(
                        mode == ConnectionMode.REQUIRE_NEW,
                        "Required existing connection but no existing connection was found");

                Connection newConn = null;
                try {
                    newConn = primaryProvider.acquire();
                    final var fNewConn = newConn;
                    final var wrapper = new TransactionConnectionWrapper(newConn);
                    yield ScopedValue.where(CURRENT, wrapper).call(() -> {
                        try {
                            wrapper.begin();
                            final var result = block.apply(fNewConn);
                            wrapper.commit();
                            return result;
                        } catch (Exception e) {
                            wrapper.rollback();
                            throw e;
                        }
                    });
                } finally {
                    if (newConn != null) {
                        primaryProvider.release(newConn);
                    }
                }
            }
        };
    }

    private Connection currentConnection() {
        var wrapper = CURRENT.orElse(null);
        return wrapper != null ? wrapper.connection() : null;
    }

    private ConnectionProvider providerOf(boolean primary) {
        return primary ? primaryProvider : replicaProvider;
    }

    @FunctionalInterface
    public interface CheckedFunction<T, R> {
        R apply(T t);
    }
}
