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
 * {@link KeyedLockProvider} backed by MySQL session-scoped {@code GET_LOCK} / {@code RELEASE_LOCK}
 * user-level locks. Same family as {@link MariaDBKeyedLockProvider} — the lock is bound
 * to the JDBC session that acquired it and is released when the session ends (lease
 * {@code close()}, {@code maxHold} expiry, or session death).
 *
 * <p><b>Why this is a separate class from the MariaDB impl.</b> The two databases agreed
 * on the {@code GET_LOCK} contract for years, but have diverged on how the timeout
 * argument is interpreted. MySQL (verified against MySQL 9.x) still honors a negative
 * timeout as "wait forever," matching the historical documentation. Modern MariaDB
 * (12.x+) rejects negative and {@code NULL} timeouts and returns {@code NULL} instead, so
 * the MariaDB impl has to substitute {@link Integer#MAX_VALUE} as an effectively-infinite
 * sentinel. Keeping the impls separate makes that divergence explicit and avoids a flag
 * that would have to be set correctly per database.
 *
 * <p><b>Targets MySQL 5.7.5+,</b> which allows multiple locks per session. Older versions
 * only support one lock per session.
 *
 * <p><b>Timeout precision is one whole second.</b> Although MySQL accepts a fractional
 * {@code DOUBLE} timeout, it rounds up to the next whole second internally (verified
 * against MySQL 9.x: a 0.5-second wait against a held lock returns in ~1s, not ~500ms).
 * Sub-second {@code maxWait} values are passed through to {@code GET_LOCK} as-is, but the
 * actual wait will be at least ceil(maxWait) seconds. This is the main behavioral
 * difference vs {@link MariaDBKeyedLockProvider}, which honors millisecond precision.
 *
 * <p>The "dirty connection" complexity that sits inside {@link PostgresKeyedLockProvider}
 * is absent here for the same reason as in the MariaDB impl: {@code GET_LOCK} accepts the
 * timeout as a function argument so a bounded acquire is one statement, with no session
 * variables to set and reset. Single eviction trigger: {@code RELEASE_LOCK} itself failing.
 */
public final class MySQLKeyedLockProvider implements KeyedLockProvider {

    private static final Logger LOG = LoggerFactory.getLogger(MySQLKeyedLockProvider.class);

    /**
     * MySQL honors a negative timeout as "wait forever," preserving the historical
     * GET_LOCK semantics. (MariaDB diverged from this in 12.x — see {@link
     * MariaDBKeyedLockProvider} for details on that side.)
     */
    private static final double WAIT_FOREVER_SECONDS = -1.0;

    private final ConnectionProvider connectionProvider;
    private final KeyedReentrantHolder<MySQLPayload> holder =
            new KeyedReentrantHolder<>("ekbatan-mysqlkeyedlock-timeout");

    private MySQLKeyedLockProvider(Builder builder) {
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
        return holder.register(key, new MySQLPayload(key, hashedKey, connection), maxHold, this::backendRelease);
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
                holder.register(key, new MySQLPayload(key, hashedKey, connection), maxHold, this::backendRelease));
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

    private void backendRelease(MySQLPayload payload) {
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

    private record MySQLPayload(String userKey, String hashedKey, Connection connection) {}

    public static final class Builder {

        private ConnectionProvider connectionProvider;

        private Builder() {}

        public static Builder mySQLKeyedLockProvider() {
            return new Builder();
        }

        public Builder connectionProvider(ConnectionProvider connectionProvider) {
            this.connectionProvider = connectionProvider;
            return this;
        }

        public MySQLKeyedLockProvider build() {
            return new MySQLKeyedLockProvider(this);
        }
    }
}
