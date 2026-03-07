package io.ekbatan.examples.postgres_single_table_events.wallet.models.events;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.examples.postgres_single_table_events.wallet.models.Wallet;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

public class WalletCreatedEvent extends ModelEvent<Wallet> {
    public final UUID ownerId;
    public final Currency currency;
    public final BigDecimal initialBalance;

    public WalletCreatedEvent(Id<Wallet> walletId, UUID ownerId, Currency currency, BigDecimal initialBalance) {
        super(walletId.getValue().toString(), Wallet.class);
        this.ownerId = ownerId;
        this.currency = currency;
        this.initialBalance = initialBalance;
    }
}
