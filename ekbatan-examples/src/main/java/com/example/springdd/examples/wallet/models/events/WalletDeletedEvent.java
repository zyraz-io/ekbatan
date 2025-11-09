package com.example.springdd.examples.wallet.models.events;

import com.example.springdd.core.domain.Id;
import com.example.springdd.core.domain.ModelEvent;
import com.example.springdd.examples.wallet.models.Wallet;

public class WalletDeletedEvent extends ModelEvent<Wallet> {

    public WalletDeletedEvent(Id<Wallet> walletId) {
        super(walletId.getValue().toString(), Wallet.class);
    }
}
