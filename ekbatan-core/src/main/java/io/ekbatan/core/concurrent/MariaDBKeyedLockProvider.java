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

/**
 * {@link KeyedLockProvider} backed by MariaDB / MySQL session-scoped {@code GET_LOCK} /
 * {@code RELEASE_LOCK} user-level locks. Same family as {@link PostgresKeyedLockProvider}:
 * the lock is bound to the JDBC session that acquired it and is released when the session
 * ends (lease {@code close()}, {@code maxHold} expiry, or session death).
 *
 * <p><b>Why this is simpler than the Postgres impl.</b> {@code GET_LOCK(name, timeout)}
 * takes the wait timeout as a function argument, so a bounded acquire is one statement.
 * Postgres' {@code pg_advisory_lock(key)} doesn't accept a timeout, forcing a
 * {@code SET lock_timeout} / {@code RESET} dance that can leave a connection in an
 * unexpected session state if the reset fails. None of that exists here — the only failure
 * that can corrupt the connection's state is {@code RELEASE_LOCK} itself failing, so that
 * is the single eviction trigger.
 *
 * <p><b>Targets MariaDB 10.0.2+ / MySQL 5.7.5+.</b> These versions accept a fractional
 * {@code DOUBLE} timeout (so {@code Duration} maps cleanly to millisecond-ish precision)
 * and allow multiple locks per session. Older versions silently round sub-second waits
 * and only allow one lock per session — both regressions if you must support them.
 *
 * <p><b>Galera caveat.</b> {@code GET_LOCK} is <i>node-local</i> in MariaDB Galera Cluster
 * — two clients connected to different nodes can both acquire the same lock simultaneously.
 * This implementation is safe for single-node MariaDB or primary-only deployments
 * (asynchronous / semi-synchronous replication). For Galera multi-master, a token+TTL
 * implementation is required instead.
 */
public final class MariaDBKeyedLockProvider implements KeyedLockProvider {

    private static final Logger LOG = LoggerFactory.getLogger(MariaDBKeyedLockProvider.class);

    /**
     * Effectively-infinite GET_LOCK timeout used by {@link #acquire(Object, Duration)}
     * (~68 years). Older MariaDB documentation suggested {@code -1} or {@code NULL} mean
     * "wait forever," but current servers (verified against MariaDB 12.x) reject both with
     * a NULL response — the timeout is mandatory and must be a non-negative finite number.
     * Passing the largest practical finite value preserves the indefinite-block semantic
     * promised by {@link KeyedLockProvider#acquire}.
     */
    private static final double WAIT_FOREVER_SECONDS = Integer.MAX_VALUE;

    private final ConnectionProvider connectionProvider;
    private final KeyedReentrantHolder<MariaDBPayload> holder =
            new KeyedReentrantHolder<>("ekbatan-mdbkeyedlock-timeout");

    private MariaDBKeyedLockProvider(Builder builder) {
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
        final boolean acquired;
        try {
            acquired = getLock(connection, hashedKey, WAIT_FOREVER_SECONDS);
        } catch (SQLException e) {
            connectionProvider.release(connection);
            throw new RuntimeException("Failed to acquire user lock for key " + key, e);
        }
        if (!acquired) {
            // GET_LOCK with a negative timeout normally waits forever; getting a non-1
            // result means the wait was disrupted (e.g. session killed by the server,
            // or a server-side error returned NULL).
            connectionProvider.release(connection);
            throw new RuntimeException(
                    "Failed to acquire user lock for key " + key + " (server-side disruption during wait)");
        }
        return holder.register(key, new MariaDBPayload(key, hashedKey, connection), maxHold, this::backendRelease);
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
        final boolean acquired;
        try {
            acquired = getLock(connection, hashedKey, toGetLockTimeout(maxWait));
        } catch (SQLException e) {
            connectionProvider.release(connection);
            throw new RuntimeException("Failed to acquire user lock for key " + key, e);
        }
        if (!acquired) {
            connectionProvider.release(connection);
            return Optional.empty();
        }
        return Optional.of(
                holder.register(key, new MariaDBPayload(key, hashedKey, connection), maxHold, this::backendRelease));
    }

    private static String hash(String key) {
        return Long.toHexString(
                sipHash24().hashString(key, StandardCharsets.UTF_8).asLong());
    }

    /**
     * Maps {@link Duration} to the fractional-seconds value expected by
     * {@code GET_LOCK(name, timeout)}. {@link Duration#ZERO} maps to {@code 0} (try-once);
     * positive durations map to seconds with a 1ms floor so a sub-millisecond wait is not
     * silently truncated to "try-once".
     */
    private static double toGetLockTimeout(Duration maxWait) {
        if (maxWait.isZero()) {
            return 0.0;
        }
        return Math.max(1L, maxWait.toMillis()) / 1000.0;
    }

    /**
     * Calls {@code GET_LOCK} and returns true iff the server returned 1. False covers both
     * 0 (acquisition didn't succeed within the timeout) and NULL (rare server-side error
     * such as out-of-memory). NULL is logged at warn level so operational issues remain
     * observable while keeping the call-site logic simple.
     */
    private static boolean getLock(Connection conn, String hashedKey, double timeoutSeconds) throws SQLException {
        try (var stmt = conn.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            stmt.setString(1, hashedKey);
            stmt.setDouble(2, timeoutSeconds);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                var value = rs.getObject(1);
                if (value == null) {
                    LOG.warn("GET_LOCK for hashedKey {} returned NULL (server-side error)", hashedKey);
                    return false;
                }
                return ((Number) value).intValue() == 1;
            }
        }
    }

    private void backendRelease(MariaDBPayload payload) {
        Integer result = null;
        try (var stmt = payload.connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            stmt.setString(1, payload.hashedKey);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    var value = rs.getObject(1);
                    result = value == null ? null : ((Number) value).intValue();
                }
            }
        } catch (SQLException e) {
            LOG.error("Failed to RELEASE_LOCK for key {}; evicting connection", payload.userKey, e);
            connectionProvider.evict(payload.connection);
            return;
        }

        if (result == null) {
            LOG.warn("RELEASE_LOCK for key {} returned NULL (no such lock)", payload.userKey);
        } else if (result != 1) {
            LOG.warn("RELEASE_LOCK for key {} returned {} (lock not held by this session)", payload.userKey, result);
        }
        // RELEASE_LOCK ran cleanly at the JDBC layer regardless of result value, so the
        // connection's session state is untouched and safe to return to the pool.
        connectionProvider.release(payload.connection);
    }

    private record MariaDBPayload(String userKey, String hashedKey, Connection connection) {}

    public static final class Builder {

        private ConnectionProvider connectionProvider;

        private Builder() {}

        public static Builder mariaDBKeyedLockProvider() {
            return new Builder();
        }

        public Builder connectionProvider(ConnectionProvider connectionProvider) {
            this.connectionProvider = connectionProvider;
            return this;
        }

        public MariaDBKeyedLockProvider build() {
            return new MariaDBKeyedLockProvider(this);
        }
    }
}
