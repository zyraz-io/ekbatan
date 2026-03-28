package io.ekbatan.test.postgres_single_table_events.wallet.models.events;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.test.postgres_single_table_events.wallet.models.Wallet;
import java.math.BigDecimal;

/**
 * Event raised when money is deposited into a wallet.
 */
public class WalletMoneyDepositedEvent extends ModelEvent<Wallet> {
    public final BigDecimal amount;
    public final BigDecimal newBalance;

    /**
     * Creates a new WalletMoneyDepositedEvent.
     *
     * @param walletId The ID of the wallet
     * @param amount The amount that was deposited
     * @param newBalance The new balance after the deposit
     */
    public WalletMoneyDepositedEvent(Id<Wallet> walletId, BigDecimal amount, BigDecimal newBalance) {
        super(walletId.getValue().toString(), Wallet.class);
        this.amount = amount;
        this.newBalance = newBalance;
    }
}
