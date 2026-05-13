package io.example.wallet.model.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.domain.ModelId;
import io.example.wallet.model.Wallet;
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

    @JsonCreator
    private WalletMoneyDepositedEvent(
            @JsonProperty("modelId") String modelId,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("newBalance") BigDecimal newBalance) {
        super(modelId, Wallet.class);
        this.amount = amount;
        this.newBalance = newBalance;
    }
}
