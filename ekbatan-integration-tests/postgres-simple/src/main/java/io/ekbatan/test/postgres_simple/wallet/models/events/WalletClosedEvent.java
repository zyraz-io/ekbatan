package io.ekbatan.test.postgres_simple.wallet.models.events;

import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.domain.ModelId;
import io.ekbatan.test.postgres_simple.wallet.models.Wallet;
import java.util.UUID;

public class WalletClosedEvent extends ModelEvent<Wallet> {

    public WalletClosedEvent(ModelId<UUID> walletId) {
        super(walletId.getId().toString(), Wallet.class);
    }
}
