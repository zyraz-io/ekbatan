package com.example.springdd.examples.wallet.models.events;

import com.example.springdd.core.domain.Id;
import com.example.springdd.core.domain.ModelEvent;
import com.example.springdd.examples.wallet.models.Wallet;
import java.math.BigDecimal;
import java.util.Currency;

public class WalletCreatedEvent extends ModelEvent<Wallet> {
    public final String ownerId;
    public final Currency currency;
    public final BigDecimal initialBalance;

    public WalletCreatedEvent(Id<Wallet> walletId, String ownerId, Currency currency, BigDecimal initialBalance) {
        super(walletId.getValue().toString(), Wallet.class);
        this.ownerId = ownerId;
        this.currency = currency;
        this.initialBalance = initialBalance;
    }
}
