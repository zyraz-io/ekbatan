package io.ekbatan.examples.wallet.repository;

import static io.ekbatan.examples.generated.jooq.tables.Wallets.WALLETS;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.repository.JooqBaseModelRepository;
import io.ekbatan.examples.generated.jooq.tables.Wallets;
import io.ekbatan.examples.generated.jooq.tables.records.WalletsRecord;
import io.ekbatan.examples.wallet.models.Wallet;
import io.ekbatan.examples.wallet.models.WalletBuilder;
import io.ekbatan.examples.wallet.models.WalletState;
import java.util.Currency;
import java.util.UUID;

public class WalletRepository extends JooqBaseModelRepository<Wallet, WalletsRecord, Wallets, UUID> {

    public WalletRepository(TransactionManager transactionManager) {
        super(Wallet.class, WALLETS, WALLETS.ID, transactionManager);
    }

    @Override
    public Wallet fromRecord(WalletsRecord record) {
        return WalletBuilder.wallet()
                .id(Id.of(Wallet.class, record.getId()))
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
