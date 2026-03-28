package io.ekbatan.core.test.repository;

import static io.ekbatan.core.test.generated.jooq.tables.Dummies.DUMMIES;
import static io.ekbatan.core.test.model.DummyBuilder.dummy;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.repository.ModelRepository;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.test.generated.jooq.tables.Dummies;
import io.ekbatan.core.test.generated.jooq.tables.records.DummiesRecord;
import io.ekbatan.core.test.model.Dummy;
import io.ekbatan.core.test.model.DummyState;
import java.util.Currency;
import java.util.UUID;

public class DummyRepository extends ModelRepository<Dummy, DummiesRecord, Dummies, UUID> {

    public DummyRepository(DatabaseRegistry databaseRegistry) {
        super(Dummy.class, DUMMIES, DUMMIES.ID, databaseRegistry);
    }

    @Override
    public Dummy fromRecord(DummiesRecord record) {
        return dummy().id(Id.of(Dummy.class, record.getId()))
                .version(record.getVersion())
                .state(DummyState.valueOf(record.getState()))
                .ownerId(record.getOwnerId())
                .currency(Currency.getInstance(record.getCurrency()))
                .balance(record.getBalance())
                .createdDate(record.getCreatedDate())
                .updatedDate(record.getUpdatedDate())
                .build();
    }

    @Override
    public DummiesRecord toRecord(Dummy model) {
        return new DummiesRecord(
                model.id.getValue(),
                model.version,
                model.state.name(),
                model.ownerId,
                model.currency.getCurrencyCode(),
                model.balance,
                model.createdDate,
                model.updatedDate);
    }
}
