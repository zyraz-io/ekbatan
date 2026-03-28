package io.ekbatan.core.shard;

import java.util.Objects;

public final class ShardIdentifier {

    public static final ShardIdentifier DEFAULT = new ShardIdentifier(0, 0);

    public final int group;
    public final int member;

    private ShardIdentifier(int group, int member) {
        this.group = group;
        this.member = member;
    }

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
