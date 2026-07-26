package io.ekbatan.core.concurrent;

import io.ekbatan.core.internal.LockKeyHash;
import io.ekbatan.core.internal.Validate;
import io.ekbatan.core.persistence.ConnectionProvider;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
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
 * unexpected session state if the reset fails. None of that exists here - the only failure
 * that can corrupt the connection's state is {@code RELEASE_LOCK} itself failing, so that
 * is the single eviction trigger.
 *
 * <p><b>Targets MariaDB 10.0.2+ / MySQL 5.7.5+.</b> These versions accept a fractional
 * {@code DOUBLE} timeout (so {@code Duration} maps cleanly to millisecond-ish precision)
 * and allow multiple locks per session. Older versions silently round sub-second waits
 * and only allow one lock per session - both regressions if you must support them.
 *
 * <p><b>Galera caveat.</b> {@code GET_LOCK} is <i>node-local</i> in MariaDB Galera Cluster
 * - two clients connected to different nodes can both acquire the same lock simultaneously.
 * This implementation is safe for single-node MariaDB or primary-only deployments
 * (asynchronous / semi-synchronous replication). For Galera multi-master, a token+TTL
 * implementation is required instead.
 */
public final class MariaDBKeyedLockProvider implements KeyedLockProvider {

    private static final Logger LOG = LoggerFactory.getLogger(MariaDBKeyedLockProvider.class);

    /**
     * How long each segment of a blocking {@link #acquire} waits inside the database before
     * returning to check for interruption and starting the next segment. See
     * {@link PostgresKeyedLockProvider} for the full rationale behind the five-second choice.
     */
    private static final Duration ACQUIRE_SEGMENT = Duration.ofSeconds(5);

    /** Outcome of one {@code GET_LOCK} call. {@code TIMED_OUT} is retryable; {@code SERVER_ERROR} is not. */
    private enum LockResult {
        ACQUIRED,
        TIMED_OUT,
        SERVER_ERROR
    }

    private final ConnectionProvider connectionProvider;
    private final KeyedReentrantHolder<MariaDBPayload> holder =
            new KeyedReentrantHolder<>("ekbatan-mdbkeyedlock-timeout");

    private MariaDBKeyedLockProvider(Builder builder) {
        this.connectionProvider = Validate.notNull(builder.connectionProvider, "connectionProvider is required");
    }

    @Override
    public Lease acquire(String key, Duration maxHold) throws InterruptedException {
        Validate.notBlank(key, "key cannot be blank");
        Validate.notNull(maxHold, "maxHold cannot be null");
        Validate.isTrue(!maxHold.isNegative() && !maxHold.isZero(), "maxHold must be positive");

        // Waiting in bounded segments rather than one GET_LOCK call with an effectively infinite
        // timeout. A single blocking call parks the thread inside a JDBC socket read, which
        // Thread.interrupt() cannot break - so the InterruptedException this method declares could
        // never be thrown. Segmenting also keeps the connection sending traffic every
        // ACQUIRE_SEGMENT, so idle connection reapers stop silently dropping long waits.
        // Reentry is handled by tryAcquire's own holder.tryReenter on the first pass.
        while (true) {
            if (Thread.interrupted()) {
                throw new InterruptedException("Interrupted while acquiring lock for key " + key);
            }
            final var lease = tryAcquire(key, ACQUIRE_SEGMENT, maxHold);
            if (lease.isPresent()) {
                return lease.get();
            }
        }
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
        final LockResult result;
        try {
            result = getLock(connection, hashedKey, toGetLockTimeout(maxWait));
        } catch (SQLException e) {
            final var failure = new LockAcquisitionException(key, "GET_LOCK failed", e);
            releaseQuietly(connection, failure);
            throw failure;
        }
        if (result == LockResult.SERVER_ERROR) {
            final var failure = new LockAcquisitionException(key, "GET_LOCK returned NULL (server-side error)");
            releaseQuietly(connection, failure);
            throw failure;
        }
        if (result == LockResult.TIMED_OUT) {
            connectionProvider.release(connection);
            return Optional.empty();
        }
        return Optional.of(
                holder.register(key, new MariaDBPayload(key, hashedKey, connection), maxHold, this::backendRelease));
    }

    private static String hash(String key) {
        return Long.toHexString(LockKeyHash.hashUtf8(key));
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
     * Calls {@code GET_LOCK} and classifies the outcome. {@code 1} is {@link LockResult#ACQUIRED},
     * {@code 0} is {@link LockResult#TIMED_OUT}, and NULL (or an empty result set) is
     * {@link LockResult#SERVER_ERROR} - a rare server-side failure such as out-of-memory.
     *
     * <p>Timed-out and server-error must stay distinguishable: {@link #acquire} retries the
     * former segment after segment, and treating a persistent server error the same way would
     * spin forever instead of failing.
     */
    private static LockResult getLock(Connection conn, String hashedKey, double timeoutSeconds) throws SQLException {
        try (var stmt = conn.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            stmt.setString(1, hashedKey);
            stmt.setDouble(2, timeoutSeconds);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return LockResult.SERVER_ERROR;
                }
                var value = rs.getObject(1);
                if (value == null) {
                    return LockResult.SERVER_ERROR;
                }
                return ((Number) value).intValue() == 1 ? LockResult.ACQUIRED : LockResult.TIMED_OUT;
            }
        }
    }

    /**
     * Returns the connection while an acquisition failure is being reported. Returning a broken
     * connection can itself throw - exactly the case this path handles - so the cleanup failure
     * is attached to {@code failure} rather than replacing it.
     */
    private void releaseQuietly(Connection connection, Throwable failure) {
        try {
            connectionProvider.release(connection);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void backendRelease(MariaDBPayload payload, KeyedReentrantHolder.ReleaseReason reason) {
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

    /** Fluent builder for {@link MariaDBKeyedLockProvider}. Obtain via {@link #mariaDBKeyedLockProvider()}. */
    public static final class Builder {

        private ConnectionProvider connectionProvider;

        private Builder() {}

        /** {@return a fresh builder for {@link MariaDBKeyedLockProvider}} */
        public static Builder mariaDBKeyedLockProvider() {
            return new Builder();
        }

        /**
         * Sets the database connection provider.
         *
         * @param connectionProvider the provider whose pool the locks will run on.
         * @return this builder, for chaining.
         */
        public Builder connectionProvider(ConnectionProvider connectionProvider) {
            this.connectionProvider = connectionProvider;
            return this;
        }

        /** {@return a configured {@link MariaDBKeyedLockProvider}} */
        public MariaDBKeyedLockProvider build() {
            return new MariaDBKeyedLockProvider(this);
        }
    }
}
