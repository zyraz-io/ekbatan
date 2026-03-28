package io.ekbatan.core.shard;

public class CrossShardException extends RuntimeException {

    public final ShardIdentifier activeShard;
    public final ShardIdentifier requestedShard;

    public CrossShardException(ShardIdentifier activeShard, ShardIdentifier requestedShard) {
        super("Cross-shard operation detected: action started on shard " + activeShard
                + " but attempted to access shard " + requestedShard);
        this.activeShard = activeShard;
        this.requestedShard = requestedShard;
    }
}
