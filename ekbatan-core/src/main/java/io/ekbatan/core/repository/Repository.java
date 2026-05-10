package io.ekbatan.core.repository;

import io.ekbatan.core.domain.Persistable;
import io.ekbatan.core.shard.ShardingStrategy;
import java.util.Collection;
import java.util.List;

/**
 * The minimum contract a repository must satisfy for the framework to persist
 * {@link Persistable}s through it: add / update operations and a {@link ShardingStrategy}
 * the executor uses to route each row to the correct shard.
 *
 * <p>Concrete repositories typically extend {@code AbstractRepository}, which provides jOOQ-
 * backed implementations of these methods plus a much richer query surface (findById,
 * findAllByIds, findOneWhere, findAllWhere, count, existsById, dbs/scatter-gather, etc.).
 * Implementing {@code Repository} directly is reserved for unusual storage backends.
 *
 * <p>All write operations are optimistic-locked via {@link Persistable#getVersion()} — see
 * {@link io.ekbatan.core.repository.exception.StaleRecordException}.
 *
 * @param <PERSISTABLE> the {@link io.ekbatan.core.domain.Model} or
 *                      {@link io.ekbatan.core.domain.Entity} subclass this repository persists
 */
public interface Repository<PERSISTABLE extends Persistable<?>> {

    /** {@return the strategy the executor consults to map each row to a shard} */
    ShardingStrategy<?> shardingStrategy();

    /**
     * Inserts a single domain object and returns it (with any database-generated values populated).
     *
     * @param model the entity to insert.
     * @return the inserted entity, post-insert.
     */
    PERSISTABLE add(PERSISTABLE model);

    /**
     * Inserts without returning the result — preferred for batched writes where the caller
     * doesn't need the post-insert state.
     *
     * @param model the entity to insert.
     */
    void addNoResult(PERSISTABLE model);

    /**
     * Inserts a batch and returns them in input order, with database-generated values populated.
     *
     * @param models the entities to insert.
     * @return the inserted entities in the same order.
     */
    List<PERSISTABLE> addAll(Collection<PERSISTABLE> models);

    /**
     * Inserts a batch without returning the results.
     *
     * @param models the entities to insert.
     */
    void addAllNoResult(Collection<PERSISTABLE> models);

    /**
     * Updates a single domain object — optimistic-locked on {@link Persistable#getVersion}.
     *
     * @param model the entity to update.
     * @return the updated entity, with {@code version + 1}.
     */
    PERSISTABLE update(PERSISTABLE model);

    /**
     * Updates without returning the result.
     *
     * @param model the entity to update.
     */
    void updateNoResult(PERSISTABLE model);

    /**
     * Updates a batch — optimistic-locked per row.
     *
     * @param models the entities to update.
     * @return the updated entities in the same order.
     */
    List<PERSISTABLE> updateAll(Collection<PERSISTABLE> models);

    /**
     * Updates a batch without returning the results.
     *
     * @param models the entities to update.
     */
    void updateAllNoResult(Collection<PERSISTABLE> models);

    /** {@return every row in this repository's table (scatter-gather across shards if sharded)} */
    List<PERSISTABLE> findAll();
}
