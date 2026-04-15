package io.ekbatan.test.event_pipeline.common.wallet.repository;

import static io.ekbatan.test.event_pipeline.common.generated.jooq.public_schema.Tables.WALLETS;
import static io.ekbatan.test.event_pipeline.common.wallet.models.WalletBuilder.wallet;

import io.ekbatan.core.domain.GenericState;
import io.ekbatan.core.domain.Id;
import io.ekbatan.core.repository.ModelRepository;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.NoShardingStrategy;
import io.ekbatan.test.event_pipeline.common.generated.jooq.public_schema.tables.Wallets;
import io.ekbatan.test.event_pipeline.common.generated.jooq.public_schema.tables.records.WalletsRecord;
import io.ekbatan.test.event_pipeline.common.wallet.models.Wallet;
import java.util.UUID;

public class WalletRepository extends ModelRepository<Wallet, WalletsRecord, Wallets, UUID> {

    public WalletRepository(DatabaseRegistry databaseRegistry) {
        super(Wallet.class, WALLETS, WALLETS.ID, databaseRegistry, new NoShardingStrategy<>());
    }

    @Override
    public Wallet fromRecord(WalletsRecord record) {
        return wallet().id(Id.of(Wallet.class, record.getId()))
                .version(record.getVersion())
                .state(GenericState.valueOf(record.getState()))
                .name(record.getName())
                .balance(record.getBalance())
                .createdDate(record.getCreatedDate())
                .updatedDate(record.getUpdatedDate())
                .build();
    }

    @Override
    public WalletsRecord toRecord(Wallet model) {
        return new WalletsRecord(
                model.id.getValue(),
                model.version,
                model.state.name(),
                model.name,
                model.balance,
                model.createdDate,
                model.updatedDate);
    }
}
