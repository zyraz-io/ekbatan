package io.ekbatan.test.postgres_sharded.wallet.repository;

import static io.ekbatan.test.postgres_sharded.generated.jooq.public_schema.Tables.WALLETS;
import static io.ekbatan.test.postgres_sharded.wallet.models.WalletBuilder.wallet;

import io.ekbatan.core.domain.ShardedId;
import io.ekbatan.core.repository.ModelRepository;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.EmbeddedBitsShardingStrategy;
import io.ekbatan.core.shard.ShardedUUID;
import io.ekbatan.test.postgres_sharded.generated.jooq.public_schema.tables.Wallets;
import io.ekbatan.test.postgres_sharded.generated.jooq.public_schema.tables.records.WalletsRecord;
import io.ekbatan.test.postgres_sharded.wallet.models.Wallet;
import io.ekbatan.test.postgres_sharded.wallet.models.WalletState;
import java.util.Currency;
import java.util.UUID;

public class WalletRepository extends ModelRepository<Wallet, WalletsRecord, Wallets, UUID> {

    public WalletRepository(DatabaseRegistry databaseRegistry) {
        super(Wallet.class, WALLETS, WALLETS.ID, databaseRegistry, new EmbeddedBitsShardingStrategy());
    }

    @Override
    public Wallet fromRecord(WalletsRecord record) {
        return wallet().id(ShardedId.of(Wallet.class, ShardedUUID.from(record.getId())))
                .version(record.getVersion())
                .state(WalletState.valueOf(record.getState()))
                .ownerId(record.getOwnerId())
                .currency(Currency.getInstance(record.getCurrency()))
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
                model.ownerId,
                model.currency.getCurrencyCode(),
                model.balance,
                model.createdDate,
                model.updatedDate);
    }
}
