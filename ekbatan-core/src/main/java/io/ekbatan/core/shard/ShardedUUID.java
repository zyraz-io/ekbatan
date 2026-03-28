package io.ekbatan.core.shard;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Value object wrapping a UUID v7 with shard info (group + member) encoded in rand_b.
 *
 * <p>Fixed layout: 4-bit group + 8-bit member = 12 shard bits, 50 random bits remaining in rand_b.
 *
 * <p>UUID v7 bit layout:
 * <pre>
 * MSB: [48-bit timestamp][4-bit version=0111][12-bit rand_a]
 * LSB: [2-bit variant=10][4-bit group][8-bit member][50-bit random]
 * </pre>
 */
public final class ShardedUUID implements ShardAwareId {

    public static final int GROUP_BITS = 4;
    public static final int MEMBER_BITS = 8;

    private static final int SHARD_BITS = GROUP_BITS + MEMBER_BITS;
    private static final int RANDOM_BITS = 62 - SHARD_BITS;

    private final UUID value;

    private ShardedUUID(UUID value) {
        this.value = value;
    }

    public UUID value() {
        return value;
    }

    public static ShardedUUID from(UUID uuid) {
        return new ShardedUUID(uuid);
    }

    public static ShardedUUID generate(ShardIdentifier shard) {
        long timestamp = System.currentTimeMillis();

        // MSB: [48-bit timestamp][4-bit version=0111][12-bit rand_a]
        long msb = (timestamp & 0xFFFFFFFFFFFFL) << 16;
        msb |= 0x7000L;
        msb |= (ThreadLocalRandom.current().nextLong() & 0xFFF);

        // LSB: [2-bit variant=10][4-bit group][8-bit member][50-bit random]
        long shardBits = ((long) shard.group << MEMBER_BITS) | shard.member;
        long randomPart = ThreadLocalRandom.current().nextLong() & ((1L << RANDOM_BITS) - 1);

        long lsb = 0x8000000000000000L;
        lsb |= (shardBits << RANDOM_BITS);
        lsb |= randomPart;

        return new ShardedUUID(new UUID(msb, lsb));
    }

    @Override
    public ShardIdentifier resolveShardIdentifier() {
        long lsb = value.getLeastSignificantBits();
        long shardBits = (lsb >>> RANDOM_BITS) & ((1L << SHARD_BITS) - 1);

        int member = (int) (shardBits & ((1L << MEMBER_BITS) - 1));
        int group = (int) ((shardBits >>> MEMBER_BITS) & ((1L << GROUP_BITS) - 1));

        return ShardIdentifier.of(group, member);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShardedUUID that)) return false;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
