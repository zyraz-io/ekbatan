package io.ekbatan.examples.postgres_single_table_events.wallet.models.events;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.examples.postgres_single_table_events.wallet.models.Wallet;

public class WalletDeletedEvent extends ModelEvent<Wallet> {

    public WalletDeletedEvent(Id<Wallet> walletId) {
        super(walletId.getValue().toString(), Wallet.class);
    }
}
