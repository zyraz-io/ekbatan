package io.ekbatan.core.repository;

import io.ekbatan.core.domain.Entity;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardingStrategy;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableRecord;

/**
 * Base repository implementation using JOOQ for database operations on Entity classes.
 *
 * @param <ENTITY> The domain entity type
 * @param <RECORD> The JOOQ record type
 * @param <DB_ID>  The ID type of the entity
 * @param <TABLE>  The JOOQ table type
 */
public abstract class EntityRepository<
                ENTITY extends Entity<ENTITY, ?, ?>,
                RECORD extends TableRecord<?>,
                TABLE extends Table<RECORD>,
                DB_ID extends Comparable<DB_ID>>
        extends AbstractRepository<ENTITY, RECORD, TABLE, DB_ID> {

    /**
     * Convenience constructor for single-shard repositories.
     *
     * @param entityClass runtime {@link Class} of the entity type.
     * @param table the jOOQ-generated table.
     * @param idField the jOOQ-generated id field.
     * @param databaseRegistry the registry of connection pools / transaction managers.
     */
    protected EntityRepository(
            Class<ENTITY> entityClass,
            TABLE table,
            TableField<RECORD, DB_ID> idField,
            DatabaseRegistry databaseRegistry) {
        super(entityClass, table, idField, databaseRegistry);
    }

    /**
     * Primary constructor for sharded entity repositories.
     *
     * @param entityClass runtime {@link Class} of the entity type.
     * @param table the jOOQ-generated table.
     * @param idField the jOOQ-generated id field.
     * @param databaseRegistry the registry of connection pools / transaction managers.
     * @param shardingStrategy strategy that maps each row's ID to a {@link io.ekbatan.core.shard.ShardIdentifier}.
     */
    protected EntityRepository(
            Class<ENTITY> entityClass,
            TABLE table,
            TableField<RECORD, DB_ID> idField,
            DatabaseRegistry databaseRegistry,
            ShardingStrategy<DB_ID> shardingStrategy) {
        super(entityClass, table, idField, databaseRegistry, shardingStrategy);
    }

    @Override
    protected String getDomainTypeName() {
        return "Entity";
    }
}
