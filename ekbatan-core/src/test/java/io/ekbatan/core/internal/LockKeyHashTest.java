package io.ekbatan.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LockKeyHashTest {

    // Canonical NIST SHA-256 test vectors, truncated to first 8 bytes read as big-endian long.
    // SHA-256("")    = e3b0c44298fc1c14 9afbf4c8996fb924 ...
    // SHA-256("abc") = ba7816bf8f01cfea 414140de5dae2223 ...

    @Test
    void empty_input_matches_truncated_sha256_of_empty() {
        assertThat(LockKeyHash.hash(new byte[0])).isEqualTo(0xe3b0c44298fc1c14L);
    }

    @Test
    void abc_matches_truncated_sha256_of_abc() {
        assertThat(LockKeyHash.hashUtf8("abc")).isEqualTo(0xba7816bf8f01cfeaL);
    }

    @Test
    void hashUtf8_is_byte_equivalent_to_hash_of_utf8_bytes() {
        String key = "wallet:abc-123";
        assertThat(LockKeyHash.hashUtf8(key)).isEqualTo(LockKeyHash.hash(key.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void deterministic_for_same_input() {
        String key = "wallet:repeated-key";
        long first = LockKeyHash.hashUtf8(key);
        long second = LockKeyHash.hashUtf8(key);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void distinct_inputs_produce_distinct_outputs() {
        // Not a guarantee for any 64-bit hash (collisions exist), but a sanity check that
        // the function actually mixes input rather than e.g. always returning zero.
        long a = LockKeyHash.hashUtf8("wallet:1");
        long b = LockKeyHash.hashUtf8("wallet:2");
        long c = LockKeyHash.hashUtf8("order:1");
        assertThat(a).isNotEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(b).isNotEqualTo(c);
    }

    @Test
    void handles_input_at_sha256_block_boundary() {
        // SHA-256 processes input in 64-byte blocks; verify exact-boundary input doesn't
        // trip on internal padding edge cases.
        byte[] exactly64Bytes = new byte[64];
        for (int i = 0; i < 64; i++) exactly64Bytes[i] = (byte) i;
        // No oracle for this specific value, just check it returns something stable
        // (and the previous tests already pin the algorithm against canonical vectors).
        long first = LockKeyHash.hash(exactly64Bytes);
        long second = LockKeyHash.hash(exactly64Bytes);
        assertThat(first).isEqualTo(second);
    }
}
