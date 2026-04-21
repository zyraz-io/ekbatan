package io.ekbatan.test.keyed_lock_provider.wallet.models.events;

import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.domain.ModelId;
import io.ekbatan.test.keyed_lock_provider.wallet.models.Wallet;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

public class WalletCreatedEvent extends ModelEvent<Wallet> {
    public final UUID ownerId;
    public final Currency currency;
    public final BigDecimal initialBalance;

    public WalletCreatedEvent(ModelId<UUID> walletId, UUID ownerId, Currency currency, BigDecimal initialBalance) {
        super(walletId.getId().toString(), Wallet.class);
        this.ownerId = ownerId;
        this.currency = currency;
        this.initialBalance = initialBalance;
    }
}
