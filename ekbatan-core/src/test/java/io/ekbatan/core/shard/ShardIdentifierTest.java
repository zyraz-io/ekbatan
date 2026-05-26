package io.ekbatan.core.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ShardIdentifierTest {

    @Test
    void should_accept_minimum_coordinates() {
        var shard = ShardIdentifier.of(ShardIdentifier.MIN_GROUP, ShardIdentifier.MIN_MEMBER);

        assertThat(shard.group).isEqualTo(0);
        assertThat(shard.member).isEqualTo(0);
    }

    @Test
    void should_accept_maximum_coordinates() {
        var shard = ShardIdentifier.of(ShardIdentifier.MAX_GROUP, ShardIdentifier.MAX_MEMBER);

        assertThat(shard.group).isEqualTo(255);
        assertThat(shard.member).isEqualTo(63);
    }

    @Test
    void should_reject_group_below_range() {
        assertThatThrownBy(() -> ShardIdentifier.of(-1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("group");
    }

    @Test
    void should_reject_group_above_range() {
        assertThatThrownBy(() -> ShardIdentifier.of(256, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("group");
    }

    @Test
    void should_reject_member_below_range() {
        assertThatThrownBy(() -> ShardIdentifier.of(0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("member");
    }

    @Test
    void should_reject_member_above_range() {
        assertThatThrownBy(() -> ShardIdentifier.of(0, 64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("member");
    }

    @Test
    void should_round_trip_maximum_coordinates_through_sharded_uuid() {
        var shard = ShardIdentifier.of(ShardIdentifier.MAX_GROUP, ShardIdentifier.MAX_MEMBER);

        var decoded = ShardedUUID.generate(shard).resolveShardIdentifier();

        assertThat(decoded).isEqualTo(shard);
    }
}
