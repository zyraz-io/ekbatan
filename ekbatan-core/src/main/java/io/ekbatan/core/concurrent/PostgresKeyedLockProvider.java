package io.ekbatan.core.concurrent;

import static com.google.common.hash.Hashing.sipHash24;

import io.ekbatan.core.persistence.ConnectionProvider;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PostgresKeyedLockProvider implements KeyedLockProvider {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresKeyedLockProvider.class);

    private static final String LOCK_NOT_AVAILABLE_SQLSTATE = "55P03";

    private final ConnectionProvider connectionProvider;

    private PostgresKeyedLockProvider(Builder builder) {
        this.connectionProvider = Validate.notNull(builder.connectionProvider, "connectionProvider is required");
    }

    @Override
    public Lease acquire(Object key, Duration maxHold) {
        Validate.notNull(key, "key cannot be null");
        Validate.notNull(maxHold, "maxHold cannot be null");
        Validate.isTrue(!maxHold.isNegative() && !maxHold.isZero(), "maxHold must be positive");

        final var hashedKey = hash(key);
        final var connection = connectionProvider.acquire();
        try {
            advisoryLock(connection, hashedKey);
        } catch (SQLException e) {
            connectionProvider.release(connection);
            throw new RuntimeException("Failed to acquire advisory lock for key " + key, e);
        }
        return startTimeout(new PgLease(this, key, hashedKey, connection, false), maxHold);
    }

    @Override
    public Optional<Lease> tryAcquire(Object key, Duration maxWait, Duration maxHold) {
        Validate.notNull(key, "key cannot be null");
        Validate.notNull(maxWait, "maxWait cannot be null");
        Validate.notNull(maxHold, "maxHold cannot be null");
        Validate.isTrue(!maxWait.isNegative(), "maxWait cannot be negative");
        Validate.isTrue(!maxHold.isNegative() && !maxHold.isZero(), "maxHold must be positive");

        final var hashedKey = hash(key);
        final var connection = connectionProvider.acquire();
        final var result = tryAdvisoryLock(connection, hashedKey, maxWait);

        if (result.error().isPresent()) {
            releaseOrEvict(connection, result.connectionDirty());
            throw new RuntimeException(
                    "Failed to acquire advisory lock for key " + key,
                    result.error().get());
        }
        if (!result.acquired()) {
            releaseOrEvict(connection, result.connectionDirty());
            return Optional.empty();
        }
        return Optional.of(
                startTimeout(new PgLease(this, key, hashedKey, connection, result.connectionDirty()), maxHold));
    }

    private PgLease startTimeout(PgLease lease, Duration maxHold) {
        lease.setTimeoutThread(
                Thread.ofVirtual().name("ekbatan-pgkeyedlock-timeout").start(() -> {
                    try {
                        Thread.sleep(maxHold);
                        lease.expire();
                    } catch (InterruptedException ignored) {
                    }
                }));
        return lease;
    }

    private static long hash(Object key) {
        return sipHash24().hashString(key.toString(), StandardCharsets.UTF_8).asLong();
    }

    private static void advisoryLock(Connection conn, long hashedKey) throws SQLException {
        try (var stmt = conn.prepareStatement("SELECT pg_advisory_lock(?)")) {
            stmt.setLong(1, hashedKey);
            stmt.execute();
        }
    }

    private record AcquireResult(boolean acquired, boolean connectionDirty, Optional<SQLException> error) {}

    private static AcquireResult tryAdvisoryLock(Connection conn, long hashedKey, Duration timeout) {
        if (timeout.isZero()) {
            try (var stmt = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                stmt.setLong(1, hashedKey);
                try (var rs = stmt.executeQuery()) {
                    return new AcquireResult(rs.next() && rs.getBoolean(1), false, Optional.empty());
                }
            } catch (SQLException e) {
                return new AcquireResult(false, false, Optional.of(e));
            }
        }

        try (var stmt = conn.createStatement()) {
            stmt.execute("SET lock_timeout = " + Math.max(1L, timeout.toMillis()));
        } catch (SQLException e) {
            return new AcquireResult(false, false, Optional.of(e));
        }

        var acquired = false;
        SQLException exception = null;
        try {
            advisoryLock(conn, hashedKey);
            acquired = true;
        } catch (SQLException e) {
            if (!LOCK_NOT_AVAILABLE_SQLSTATE.equals(e.getSQLState())) {
                exception = e;
            }
        }

        var dirty = false;
        try (var stmt = conn.createStatement()) {
            stmt.execute("SET lock_timeout = 0");
        } catch (SQLException resetEx) {
            LOG.warn("Failed to reset lock_timeout on connection; marking dirty", resetEx);
            dirty = true;
            if (exception != null) {
                exception.addSuppressed(resetEx);
            }
        }

        return new AcquireResult(acquired, dirty, Optional.ofNullable(exception));
    }

    private static void advisoryUnlock(Connection conn, long hashedKey) throws SQLException {
        try (var stmt = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            stmt.setLong(1, hashedKey);
            stmt.execute();
        }
    }

    void doRelease(Object key, long hashedKey, Connection connection, boolean connectionDirty) {
        try {
            advisoryUnlock(connection, hashedKey);
        } catch (SQLException e) {
            LOG.error("Failed to release advisory lock for key {}; evicting connection", key, e);
            connectionProvider.evict(connection);
            return;
        }
        releaseOrEvict(connection, connectionDirty);
    }

    private void releaseOrEvict(Connection connection, boolean dirty) {
        if (dirty) {
            connectionProvider.evict(connection);
        } else {
            connectionProvider.release(connection);
        }
    }

    private static final class PgLease implements Lease {

        private final PostgresKeyedLockProvider owner;
        private final Object key;
        private final long hashedKey;
        private final Connection connection;
        private final boolean connectionDirty;
        private final AtomicBoolean released = new AtomicBoolean(false);
        private volatile Thread timeoutThread;

        PgLease(
                PostgresKeyedLockProvider owner,
                Object key,
                long hashedKey,
                Connection connection,
                boolean connectionDirty) {
            this.owner = owner;
            this.key = key;
            this.hashedKey = hashedKey;
            this.connection = connection;
            this.connectionDirty = connectionDirty;
        }

        void setTimeoutThread(Thread thread) {
            this.timeoutThread = thread;
        }

        @Override
        public boolean isHeld() {
            return !released.get();
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                interruptTimeout();
                owner.doRelease(key, hashedKey, connection, connectionDirty);
            }
        }

        void expire() {
            if (released.compareAndSet(false, true)) {
                LOG.warn("PostgresKeyedLockProvider auto-released held lock for key {} (hold limit exceeded)", key);
                owner.doRelease(key, hashedKey, connection, connectionDirty);
            }
        }

        private void interruptTimeout() {
            var t = timeoutThread;
            if (t != null) {
                t.interrupt();
            }
        }
    }

    public static final class Builder {

        private ConnectionProvider connectionProvider;

        private Builder() {}

        public static Builder postgresKeyedLockProvider() {
            return new Builder();
        }

        public Builder connectionProvider(ConnectionProvider connectionProvider) {
            this.connectionProvider = connectionProvider;
            return this;
        }

        public PostgresKeyedLockProvider build() {
            return new PostgresKeyedLockProvider(this);
        }
    }
}
