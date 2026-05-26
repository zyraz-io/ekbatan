package io.example.wallet.repository;

import static io.example.wallet.generated.jooq.public_schema.tables.Wallets.WALLETS;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.repository.ModelRepository;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.di.EkbatanRepository;
import io.example.wallet.generated.jooq.public_schema.tables.Wallets;
import io.example.wallet.generated.jooq.public_schema.tables.records.WalletsRecord;
import io.example.wallet.model.Wallet;
import io.example.wallet.model.WalletBuilder;
import io.example.wallet.model.WalletState;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@EkbatanRepository
public class WalletRepository extends ModelRepository<Wallet, WalletsRecord, Wallets, UUID> {

    public WalletRepository(DatabaseRegistry databaseRegistry) {
        super(Wallet.class, WALLETS, WALLETS.ID, databaseRegistry);
    }

    public List<Wallet> findAllByOwnerId(UUID ownerId) {
        return readonlyDb()
                .selectFrom(WALLETS)
                .where(WALLETS.OWNER_ID.eq(ownerId))
                .and(WALLETS.STATE.ne(WalletState.DELETED.name()))
                .fetch(this::fromRecord);
    }

    /**
     * Open wallets with balance strictly below the given threshold - consumed by
     * {@link io.example.wallet.job.WalletStipendJob} to decide who needs a top-up.
     */
    public List<Wallet> findOpenWithBalanceBelow(BigDecimal threshold) {
        return readonlyDb()
                .selectFrom(WALLETS)
                .where(WALLETS.BALANCE.lt(threshold))
                .and(WALLETS.STATE.eq(WalletState.OPENED.name()))
                .fetch(this::fromRecord);
    }

    @Override
    public Wallet fromRecord(WalletsRecord r) {
        return WalletBuilder.wallet()
                .id(Id.of(Wallet.class, r.getId()))
                .version(r.getVersion())
                .state(WalletState.valueOf(r.getState()))
                .ownerId(r.getOwnerId())
                .currency(java.util.Currency.getInstance(r.getCurrency()))
                .balance(r.getBalance())
                .createdDate(r.getCreatedDate())
                .updatedDate(r.getUpdatedDate())
                .build();
    }

    @Override
    public WalletsRecord toRecord(Wallet w) {
        return new WalletsRecord(
                w.id.getValue(),
                w.version,
                w.state.name(),
                w.ownerId,
                w.currency.getCurrencyCode(),
                w.balance,
                w.createdDate,
                w.updatedDate);
    }
}
