package io.example.wallet.model.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.domain.ModelId;
import io.ekbatan.core.domain.ShardedId;
import io.example.wallet.model.Wallet;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Emitted by the wallet receiving money in a cross-shard transfer. Companion to
 * {@link WalletMoneyTransferredOutEvent} on the source wallet.
 */
public class WalletMoneyTransferredInEvent extends ModelEvent<Wallet> {

    public final UUID fromWalletId;
    public final BigDecimal amount;
    public final BigDecimal newBalance;

    public WalletMoneyTransferredInEvent(
            ModelId<UUID> walletId, ShardedId<Wallet> fromWallet, BigDecimal amount, BigDecimal newBalance) {
        super(walletId.getId().toString(), Wallet.class);
        this.fromWalletId = fromWallet.getValue();
        this.amount = amount;
        this.newBalance = newBalance;
    }

    @JsonCreator
    private WalletMoneyTransferredInEvent(
            @JsonProperty("modelId") String modelId,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("fromWalletId") UUID fromWalletId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("newBalance") BigDecimal newBalance) {
        super(modelId, Wallet.class);
        this.fromWalletId = fromWalletId;
        this.amount = amount;
        this.newBalance = newBalance;
    }
}
