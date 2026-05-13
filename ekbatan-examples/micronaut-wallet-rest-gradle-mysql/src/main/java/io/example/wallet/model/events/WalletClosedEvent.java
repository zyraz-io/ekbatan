package io.example.wallet.model.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.domain.ModelId;
import io.example.wallet.model.Wallet;
import java.util.UUID;

public class WalletClosedEvent extends ModelEvent<Wallet> {

    public WalletClosedEvent(ModelId<UUID> walletId) {
        super(walletId.getId().toString(), Wallet.class);
    }

    @JsonCreator
    private WalletClosedEvent(@JsonProperty("modelId") String modelId, @JsonProperty("modelName") String modelName) {
        super(modelId, Wallet.class);
    }
}
