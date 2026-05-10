package io.ekbatan.core.shard;

/**
 * Thrown by {@link io.ekbatan.core.action.ActionExecutor} when an action's
 * {@link io.ekbatan.core.action.ActionPlan} touches more than one shard and
 * {@link io.ekbatan.core.action.ExecutionConfiguration#allowCrossShard} is {@code false}
 * (the default).
 *
 * <p>Cross-shard actions cannot commit atomically — there is no 2PC — so the default is to
 * reject rather than fall into per-shard at-least-once semantics by accident. Set
 * {@code allowCrossShard = true} on the execution configuration to opt in explicitly when
 * the cross-shard side-effect is acceptable.
 */
public class CrossShardException extends RuntimeException {

    /** The shard the action started on (and committed its earlier writes to). */
    public final ShardIdentifier activeShard;

    /** The second shard the action attempted to touch. */
    public final ShardIdentifier requestedShard;

    /**
     * Constructs the exception with the two shards involved in the conflict.
     *
     * @param activeShard the shard the action started on.
     * @param requestedShard the second shard the action attempted to touch.
     */
    public CrossShardException(ShardIdentifier activeShard, ShardIdentifier requestedShard) {
        super("Cross-shard operation detected: action started on shard " + activeShard
                + " but attempted to access shard " + requestedShard);
        this.activeShard = activeShard;
        this.requestedShard = requestedShard;
    }
}
