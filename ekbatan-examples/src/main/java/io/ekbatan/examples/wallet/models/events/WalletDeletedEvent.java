package io.ekbatan.examples.wallet.models.events;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.examples.wallet.models.Wallet;

public class WalletDeletedEvent extends ModelEvent<Wallet> {

    public WalletDeletedEvent(Id<Wallet> walletId) {
        super(walletId.getValue().toString(), Wallet.class);
    }
}
