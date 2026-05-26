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
 * Emitted by the wallet that money is leaving in a cross-shard transfer. The counterpart wallet
 * may live on a different physical database - this event records the counterpart's ID so each
 * shard's row in {@code eventlog.events} carries the full transfer context.
 */
public class WalletMoneyTransferredOutEvent extends ModelEvent<Wallet> {

    public final UUID toWalletId;
    public final BigDecimal amount;
    public final BigDecimal newBalance;

    public WalletMoneyTransferredOutEvent(
            ModelId<UUID> walletId, ShardedId<Wallet> toWallet, BigDecimal amount, BigDecimal newBalance) {
        super(walletId.getId().toString(), Wallet.class);
        this.toWalletId = toWallet.getValue();
        this.amount = amount;
        this.newBalance = newBalance;
    }

    @JsonCreator
    private WalletMoneyTransferredOutEvent(
            @JsonProperty("modelId") String modelId,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("toWalletId") UUID toWalletId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("newBalance") BigDecimal newBalance) {
        super(modelId, Wallet.class);
        this.toWalletId = toWalletId;
        this.amount = amount;
        this.newBalance = newBalance;
    }
}
