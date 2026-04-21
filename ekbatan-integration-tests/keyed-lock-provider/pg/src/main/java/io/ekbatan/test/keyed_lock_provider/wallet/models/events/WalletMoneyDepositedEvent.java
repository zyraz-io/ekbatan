package io.ekbatan.test.keyed_lock_provider.wallet.models.events;

import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.domain.ModelId;
import io.ekbatan.test.keyed_lock_provider.wallet.models.Wallet;
import java.math.BigDecimal;
import java.util.UUID;

public class WalletMoneyDepositedEvent extends ModelEvent<Wallet> {
    public final BigDecimal amount;
    public final BigDecimal newBalance;

    public WalletMoneyDepositedEvent(ModelId<UUID> walletId, BigDecimal amount, BigDecimal newBalance) {
        super(walletId.getId().toString(), Wallet.class);
        this.amount = amount;
        this.newBalance = newBalance;
    }
}
