package io.ekbatan.examples.wallet.repository;

import static io.ekbatan.examples.wallet.models.Wallet.Builder.wallet;
import static io.ekbatan.examples.generated.jooq.tables.Wallets.WALLETS;

import io.ekbatan.core.domain.GenericState;
import io.ekbatan.core.domain.Id;
import io.ekbatan.core.repository.jooq.JooqBaseModelRepository;
import io.ekbatan.examples.wallet.models.Wallet;
import io.ekbatan.examples.generated.jooq.tables.Wallets;
import io.ekbatan.examples.generated.jooq.tables.records.WalletsRecord;
import java.util.Currency;
import java.util.UUID;
import org.jooq.DSLContext;

public class WalletRepository extends JooqBaseModelRepository<Wallet, WalletsRecord, Wallets, UUID> {

    public WalletRepository(DSLContext dsl) {
        super(Wallet.class, WALLETS, WALLETS.ID, dsl);
    }

    @Override
    public Wallet fromRecord(WalletsRecord record) {
        return wallet().id(Id.of(Wallet.class, record.getId()))
                .state(GenericState.valueOf(record.getState()))
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
                model.state.name(),
                model.ownerId,
                model.currency.getCurrencyCode(),
                model.balance,
                model.createdDate,
                model.updatedDate);
    }
}
