package io.ekbatan.core.shard;

import java.util.Objects;

/**
 * Two-tier shard coordinate: a {@code group} (logical grouping — e.g. region, tenant tier)
 * and a {@code member} (physical database within the group). The framework uses this pair
 * as the lookup key into {@link DatabaseRegistry}.
 *
 * <p>{@link #DEFAULT} (group=0, member=0) is the sentinel used by single-DB deployments and
 * by {@link io.ekbatan.core.action.ActionExecutor} when a {@link ShardingStrategy} returns
 * {@link java.util.Optional#empty()} for an aggregate's shard.
 */
public final class ShardIdentifier {

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
     * Constructs a shard identifier from raw {@code (group, member)} coordinates.
     *
     * @param group the logical group component.
     * @param member the physical member component.
     * @return a new {@link ShardIdentifier}.
     */
    public static ShardIdentifier of(int group, int member) {
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
