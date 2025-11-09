package com.example.springdd.examples.wallet.models.events;

import com.example.springdd.core.domain.Id;
import com.example.springdd.core.domain.ModelEvent;
import com.example.springdd.examples.wallet.models.Wallet;
import java.math.BigDecimal;

/**
 * Event raised when money is withdrawn from a wallet.
 */
public class WalletMoneyWithdrawnEvent extends ModelEvent<Wallet> {
    public final BigDecimal amount;
    public final BigDecimal newBalance;

    /**
     * Creates a new WalletMoneyWithdrawnEvent.
     *
     * @param walletId The ID of the wallet
     * @param amount The amount that was withdrawn
     * @param newBalance The new balance after the withdrawal
     */
    public WalletMoneyWithdrawnEvent(Id<Wallet> walletId, BigDecimal amount, BigDecimal newBalance) {
        super(walletId.getValue().toString(), Wallet.class);
        this.amount = amount;
        this.newBalance = newBalance;
    }
}
