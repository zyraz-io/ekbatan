package io.ekbatan.test.keyed_lock_provider.wallet.models.events;

import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.domain.ModelId;
import io.ekbatan.test.keyed_lock_provider.wallet.models.Wallet;
import java.util.UUID;

public class WalletClosedEvent extends ModelEvent<Wallet> {

    public WalletClosedEvent(ModelId<UUID> walletId) {
        super(walletId.getId().toString(), Wallet.class);
    }
}
