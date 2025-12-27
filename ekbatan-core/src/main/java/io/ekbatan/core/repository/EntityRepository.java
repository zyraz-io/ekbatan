package io.ekbatan.core.repository;

import io.ekbatan.core.domain.Entity;
import io.ekbatan.core.persistence.TransactionManager;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableRecord;

/**
 * Base repository implementation using JOOQ for database operations on Entity classes.
 *
 * @param <ENTITY>    The domain entity type
 * @param <RECORD>    The JOOQ record type
 * @param <ENTITY_ID> The ID type of the entity
 * @param <TABLE>     The JOOQ table type
 */
public abstract class EntityRepository<
                ENTITY extends Entity<ENTITY, ?, ?>,
                RECORD extends TableRecord<?>,
                TABLE extends Table<RECORD>,
                ENTITY_ID extends Comparable<ENTITY_ID>>
        extends AbstractRepository<ENTITY, RECORD, TABLE, ENTITY_ID> {

    protected EntityRepository(
            Class<ENTITY> entityClass,
            TABLE table,
            TableField<RECORD, ENTITY_ID> idField,
            TransactionManager transactionManager) {
        super(entityClass, table, idField, transactionManager);
    }

    @Override
    protected String getDomainTypeName() {
        return "Entity";
    }
}
