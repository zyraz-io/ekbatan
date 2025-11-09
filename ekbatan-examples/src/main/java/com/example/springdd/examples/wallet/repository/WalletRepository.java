package com.example.springdd.examples.wallet.repository;

import static com.example.springdd.examples.wallet.models.Wallet.Builder.wallet;
import static com.example.springdd.generated.jooq.tables.Wallets.WALLETS;

import com.example.springdd.core.domain.GenericState;
import com.example.springdd.core.domain.Id;
import com.example.springdd.core.repository.jooq.JooqBaseModelRepository;
import com.example.springdd.examples.wallet.models.Wallet;
import com.example.springdd.generated.jooq.tables.Wallets;
import com.example.springdd.generated.jooq.tables.records.WalletsRecord;
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
