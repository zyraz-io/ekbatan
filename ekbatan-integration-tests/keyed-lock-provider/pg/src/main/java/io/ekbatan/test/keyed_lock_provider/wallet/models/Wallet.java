package io.ekbatan.test.keyed_lock_provider.wallet.models;

import static io.ekbatan.test.keyed_lock_provider.wallet.models.WalletState.CLOSED;
import static io.ekbatan.test.keyed_lock_provider.wallet.models.WalletState.OPENED;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.Model;
import io.ekbatan.core.processor.AutoBuilder;
import io.ekbatan.test.keyed_lock_provider.wallet.models.events.WalletClosedEvent;
import io.ekbatan.test.keyed_lock_provider.wallet.models.events.WalletCreatedEvent;
import io.ekbatan.test.keyed_lock_provider.wallet.models.events.WalletMoneyDepositedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.apache.commons.lang3.Validate;

@AutoBuilder
public final class Wallet extends Model<Wallet, Id<Wallet>, WalletState> {
    public final UUID ownerId;
    public final Currency currency;
    public final BigDecimal balance;

    Wallet(WalletBuilder builder) {
        super(builder);
        this.ownerId = Validate.notNull(builder.ownerId, "ownerId cannot be null");
        this.currency = Validate.notNull(builder.currency, "currency cannot be null");
        this.balance = Validate.notNull(builder.balance, "balance cannot be null");
    }

    public static WalletBuilder createWallet(UUID ownerId, Currency currency, BigDecimal balance, Instant createdDate) {
        final var id = Id.random(Wallet.class);
        return WalletBuilder.wallet()
                .id(id)
                .state(OPENED)
                .ownerId(ownerId)
                .currency(currency)
                .balance(balance)
                .createdDate(createdDate)
                .withInitialVersion()
                .withEvent(new WalletCreatedEvent(id, ownerId, currency, balance));
    }

    @Override
    public WalletBuilder copy() {
        return WalletBuilder.wallet()
                .copyBase(this)
                .ownerId(ownerId)
                .currency(currency)
                .balance(balance);
    }

    public Wallet deposit(BigDecimal amount) {
        Validate.notNull(amount, "amount cannot be null");
        Validate.isTrue(amount.compareTo(BigDecimal.ZERO) > 0, "Deposit amount must be positive");

        final var newBalance = balance.add(amount);
        return copy().withEvent(new WalletMoneyDepositedEvent(id, amount, newBalance))
                .balance(newBalance)
                .build();
    }

    public Wallet close() {
        if (state.equals(CLOSED)) {
            return this;
        }
        return copy().withEvent(new WalletClosedEvent(id)).state(CLOSED).build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        Wallet wallet = (Wallet) o;
        return ownerId.equals(wallet.ownerId)
                && currency.equals(wallet.currency)
                && balance.compareTo(wallet.balance) == 0;
    }
}
