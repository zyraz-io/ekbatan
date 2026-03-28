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

    protected EntityRepository(
            Class<ENTITY> entityClass,
            TABLE table,
            TableField<RECORD, DB_ID> idField,
            DatabaseRegistry databaseRegistry) {
        super(entityClass, table, idField, databaseRegistry);
    }

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
