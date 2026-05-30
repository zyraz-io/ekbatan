package io.ekbatan.core.shard;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import org.apache.commons.lang3.Validate;

/**
 * Two-tier shard coordinate: a {@code group} (logical grouping - e.g. region, tenant tier)
 * and a {@code member} (physical database within the group). The framework uses this pair
 * as the lookup key into {@link DatabaseRegistry}.
 *
 * <p>{@link #DEFAULT} (group=0, member=0) is the sentinel used by single-DB deployments and
 * by {@link io.ekbatan.core.action.ActionExecutor} when a {@link ShardingStrategy} returns
 * {@link java.util.Optional#empty()} for an aggregate's shard.
 */
public final class ShardIdentifier {

    /** Minimum supported shard group. */
    public static final int MIN_GROUP = 0;

    /** Maximum supported shard group. Encoded into 8 bits. */
    public static final int MAX_GROUP = 255;

    /** Minimum supported shard member. */
    public static final int MIN_MEMBER = 0;

    /** Maximum supported shard member. Encoded into 6 bits. */
    public static final int MAX_MEMBER = 63;

    /** Sentinel used by single-DB deployments and as the framework's fallback shard. */
    public static final ShardIdentifier DEFAULT = new ShardIdentifier(0, 0);

    /** The logical group component of the shard coordinate. */
    public final int group;

    /** The physical member component within the group. */
    public final int member;

    private ShardIdentifier(int group, int member) {
        this.group = group;
        this.member = member;
    }

    /**
     * Constructs a shard identifier from raw {@code (group, member)} coordinates. The
     * coordinates must fit the bits reserved in {@link ShardedUUID}: group 0..255 and
     * member 0..63.
     *
     * @param group the logical group component.
     * @param member the physical member component.
     * @return a new {@link ShardIdentifier}.
     */
    @JsonCreator
    public static ShardIdentifier of(@JsonProperty("group") int group, @JsonProperty("member") int member) {
        Validate.inclusiveBetween(MIN_GROUP, MAX_GROUP, group, "group must be 0 or 255 or between them");
        Validate.inclusiveBetween(MIN_MEMBER, MAX_MEMBER, member, "member must be 0 or 63 or between them");
        return new ShardIdentifier(group, member);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShardIdentifier that)) return false;
        return group == that.group && member == that.member;
    }

    @Override
    public int hashCode() {
        return Objects.hash(group, member);
    }

    @Override
    public String toString() {
        return "ShardIdentifier(" + group + ", " + member + ")";
    }
}
