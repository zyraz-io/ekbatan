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
 * PostgreSQL-backed implementation of {@link KeyedLockProvider} using session-level advisory
 * locks: blocking acquires call {@code pg_advisory_lock(key)}, time-bounded acquires call
 * {@code pg_try_advisory_lock(key)} (for {@code maxWait=0}) or {@code pg_advisory_lock} with
 * a per-transaction {@code SET lock_timeout} (for bounded waits), and releases call
 * {@code pg_advisory_unlock(key)}.
 *
 * <p>Each lease holds onto its own pooled {@link java.sql.Connection} for the lifetime of
 * the lease so the Postgres session that acquired the lock is the one that releases it. The
 * connection is returned to the pool on normal release; if release fails or the connection
 * is left dirty (e.g. {@code SET lock_timeout} reset failed), the provider evicts the
 * connection from the pool instead.
 *
 * <p>Keys are hashed by {@link io.ekbatan.core.internal.LockKeyHash} - SHA-256 truncated to its
 * first 8 bytes - into Postgres's 64-bit advisory-lock identifier.
 * Collisions are statistically irrelevant for any practical key cardinality. Note that the
 * 64-bit advisory-lock identifier space is shared with anything else in the database using
 * advisory locks - if an external service uses the same Postgres instance with overlapping
 * hashes, collisions across applications are possible (though hash-distance makes them very
 * unlikely in practice).
 *
 * <p>Reentrancy is per-{@code (thread, key)} pair (see {@link KeyedLockProvider} for the
 * full contract). The local {@link KeyedReentrantHolder} short-circuits same-thread reentry
 * with no Postgres round-trip; backend acquisition happens only for the outermost lease.
 */
public final class PostgresKeyedLockProvider implements KeyedLockProvider {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresKeyedLockProvider.class);

    private static final String LOCK_NOT_AVAILABLE_SQLSTATE = "55P03";

    /**
     * How long each segment of a blocking {@link #acquire} waits inside the database before
     * returning to check for interruption and starting the next segment.
     *
     * <p>Five seconds balances two things: interrupt latency stays well inside the 30-second
     * graceful-shutdown budgets used by Kubernetes and Spring Boot, and the connection sends
     * traffic often enough that idle-connection reapers never consider it abandoned. The waiter
     * is genuinely queued inside Postgres for the duration of each segment, so hand-off from a
     * releasing holder is still immediate; the cost is that each new segment re-enters that
     * queue at the back.
     */
    private static final Duration ACQUIRE_SEGMENT = Duration.ofSeconds(5);

    private final ConnectionProvider connectionProvider;
    private final KeyedReentrantHolder<PgPayload> holder = new KeyedReentrantHolder<>("ekbatan-pgkeyedlock-timeout");

    private PostgresKeyedLockProvider(Builder builder) {
        this.connectionProvider = Validate.notNull(builder.connectionProvider, "connectionProvider is required");
    }

    @Override
    public Lease acquire(String key, Duration maxHold) throws InterruptedException {
        Validate.notBlank(key, "key cannot be blank");
        Validate.notNull(maxHold, "maxHold cannot be null");
        Validate.isTrue(!maxHold.isNegative() && !maxHold.isZero(), "maxHold must be positive");

        // Waiting in bounded segments rather than one unbounded pg_advisory_lock call. A single
        // blocking call parks the thread inside a JDBC socket read, which Thread.interrupt()
        // cannot break - so the InterruptedException this method declares could never be thrown.
        // Segmenting also keeps the connection sending traffic every ACQUIRE_SEGMENT, so idle
        // connection reapers (NAT, load balancers, PgBouncer) stop silently dropping long waits.
        //
        // Reentry is resolved before the interrupt check, not by tryAcquire on the first pass.
        // Re-taking a lock this thread already holds touches no backend and cannot block, so an
        // interrupt has nothing to abort; checking the flag first meant an interrupted thread
        // could not re-enter its own lock, which is exactly what a shutdown-path cleanup does.
        // The flag is left set rather than consumed here, so the caller's next blocking call
        // still observes it.
        final var reentered = holder.tryReenter(key);
        if (reentered.isPresent()) {
            return reentered.get();
        }

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
        // Drawing the connection is part of acquiring the lock, so its failure has to surface as
        // LockAcquisitionException like every other failure below. Unwrapped, it escaped as the
        // bare RuntimeException("Failed to acquire connection") that ConnectionProvider throws -
        // past every catch clause written against the documented type, and on the single most
        // likely failure of all, the database being unreachable. Nothing to release on this path:
        // the connection was never handed over.
        final Connection connection;
        try {
            connection = connectionProvider.acquire();
        } catch (RuntimeException e) {
            throw new LockAcquisitionException(key, "could not obtain a connection", e);
        }
        final var result = tryAdvisoryLock(connection, hashedKey, maxWait);

        if (result.error().isPresent()) {
            final var failure = new LockAcquisitionException(
                    key, "advisory lock request failed", result.error().get());
            releaseOrEvictQuietly(connection, result.connectionDirty(), failure);
            throw failure;
        }
        if (!result.acquired()) {
            releaseOrEvict(connection, result.connectionDirty());
            return Optional.empty();
        }
        return Optional.of(holder.register(
                key, new PgPayload(key, hashedKey, connection, result.connectionDirty()), maxHold, this::lockRelease));
    }

    private static long hash(String key) {
        return LockKeyHash.hashUtf8(key);
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

    private void lockRelease(PgPayload payload, KeyedReentrantHolder.ReleaseReason reason) {
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

    /**
     * Returns the connection while an acquisition failure is being reported. Returning a broken
     * connection can itself throw - and that is exactly the case this path handles - so the
     * cleanup failure is attached to {@code failure} rather than replacing it. Without this the
     * caller would see "Failed to release connection" and lose the SQL error that actually
     * explains the failed acquisition.
     */
    private void releaseOrEvictQuietly(Connection connection, boolean dirty, Throwable failure) {
        try {
            releaseOrEvict(connection, dirty);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private record PgPayload(String userKey, long hashedKey, Connection connection, boolean dirty) {}

    /** Fluent builder for {@link PostgresKeyedLockProvider}. Obtain via {@link #postgresKeyedLockProvider()}. */
    public static final class Builder {

        private ConnectionProvider connectionProvider;

        private Builder() {}

        /** {@return a fresh builder for {@link PostgresKeyedLockProvider}} */
        public static Builder postgresKeyedLockProvider() {
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

        /** {@return a configured {@link PostgresKeyedLockProvider}} */
        public PostgresKeyedLockProvider build() {
            return new PostgresKeyedLockProvider(this);
        }
    }
}
