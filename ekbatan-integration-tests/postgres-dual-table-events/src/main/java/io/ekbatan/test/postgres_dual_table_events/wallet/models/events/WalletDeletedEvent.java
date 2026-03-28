package io.ekbatan.test.postgres_dual_table_events.wallet.models.events;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.test.postgres_dual_table_events.wallet.models.Wallet;

public class WalletDeletedEvent extends ModelEvent<Wallet> {

    public WalletDeletedEvent(Id<Wallet> walletId) {
        super(walletId.getValue().toString(), Wallet.class);
    }
}
