package io.ekbatan.core.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Derives a stable 64-bit identifier from a string key, used by the keyed-lock providers
 * to feed Postgres {@code pg_advisory_xact_lock(bigint)} and MySQL/MariaDB
 * {@code GET_LOCK}.
 *
 * <p>The current implementation is SHA-256 truncated to its first 8 bytes, read as a
 * big-endian {@code long}. SHA-256 is more than the use case strictly needs - we want
 * good distribution, not cryptographic collision resistance - but it's the simplest
 * 64-bit-wide JDK-only option, with zero algorithm code we have to own. The 64-bit
 * output keeps the birthday-collision horizon at ~4.3 billion distinct keys: for any
 * realistic concurrent-lock count the probability of two distinct business keys
 * colliding is negligible (~1 in 30 million at 1M simultaneously held locks), and a
 * collision degrades performance rather than correctness (two unrelated operations
 * briefly serialize against each other).
 *
 * <p>Not part of the public API - internal to the framework's lock providers.
 */
public final class LockKeyHash {

    private LockKeyHash() {}

    /** {@return a 64-bit identifier derived from {@code value}'s UTF-8 bytes} */
    public static long hashUtf8(String value) {
        return hash(value.getBytes(StandardCharsets.UTF_8));
    }

    /** {@return a 64-bit identifier derived from {@code data}} */
    public static long hash(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return ByteBuffer.wrap(digest, 0, 8).getLong();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by every conformant JRE per the JCA specification;
            // its absence indicates a corrupted runtime, not a recoverable error.
            throw new AssertionError("SHA-256 must be available", e);
        }
    }
}
