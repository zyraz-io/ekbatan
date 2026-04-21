package io.ekbatan.core.concurrent;

import static com.google.common.hash.Hashing.sipHash24;

import io.ekbatan.core.persistence.ConnectionProvider;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PostgresKeyedLockProvider implements KeyedLockProvider {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresKeyedLockProvider.class);

    private static final String LOCK_NOT_AVAILABLE_SQLSTATE = "55P03";

    private final ConnectionProvider connectionProvider;
    private final KeyedReentrantHolder<PgPayload> holder = new KeyedReentrantHolder<>("ekbatan-pgkeyedlock-timeout");

    private PostgresKeyedLockProvider(Builder builder) {
        this.connectionProvider = Validate.notNull(builder.connectionProvider, "connectionProvider is required");
    }

    @Override
    public Lease acquire(String key, Duration maxHold) {
        Validate.notBlank(key, "key cannot be blank");
        Validate.notNull(maxHold, "maxHold cannot be null");
        Validate.isTrue(!maxHold.isNegative() && !maxHold.isZero(), "maxHold must be positive");

        var reentered = holder.tryReenter(key);
        if (reentered.isPresent()) {
            return reentered.get();
        }

        final var hashedKey = hash(key);
        final var connection = connectionProvider.acquire();
        try {
            advisoryLock(connection, hashedKey);
        } catch (SQLException e) {
            connectionProvider.release(connection);
            throw new RuntimeException("Failed to acquire advisory lock for key " + key, e);
        }
        return holder.register(key, new PgPayload(key, hashedKey, connection, false), maxHold, this::lockRelease);
    }

    @Override
    public Optional<Lease> tryAcquire(String key, Duration maxWait, Duration maxHold) {
        Validate.notBlank(key, "key cannot be blank");
        Validate.notNull(maxWait, "maxWait cannot be null");
        Validate.notNull(maxHold, "maxHold cannot be null");
        Validate.isTrue(!maxWait.isNegative(), "maxWait cannot be negative");
        Validate.isTrue(!maxHold.isNegative() && !maxHold.isZero(), "maxHold must be positive");

        var reentered = holder.tryReenter(key);
        if (reentered.isPresent()) {
            return reentered;
        }

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
        return Optional.of(holder.register(
                key, new PgPayload(key, hashedKey, connection, result.connectionDirty()), maxHold, this::lockRelease));
    }

    private static long hash(String key) {
        return sipHash24().hashString(key, StandardCharsets.UTF_8).asLong();
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

    private void lockRelease(PgPayload payload) {
        try {
            advisoryUnlock(payload.connection, payload.hashedKey);
        } catch (SQLException e) {
            LOG.error("Failed to release advisory lock for key {}; evicting connection", payload.userKey, e);
            connectionProvider.evict(payload.connection);
            return;
        }
        releaseOrEvict(payload.connection, payload.dirty);
    }

    private void releaseOrEvict(Connection connection, boolean dirty) {
        if (dirty) {
            connectionProvider.evict(connection);
        } else {
            connectionProvider.release(connection);
        }
    }

    private record PgPayload(String userKey, long hashedKey, Connection connection, boolean dirty) {}

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
