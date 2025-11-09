package com.example.springdd.examples.wallet.models;

import static com.example.springdd.core.domain.GenericState.ACTIVE;
import static com.example.springdd.core.domain.GenericState.DELETED;
import static com.example.springdd.examples.wallet.models.Wallet.Builder.wallet;

import com.example.springdd.core.domain.GenericState;
import com.example.springdd.core.domain.Id;
import com.example.springdd.core.domain.Model;
import com.example.springdd.examples.wallet.models.events.WalletCreatedEvent;
import com.example.springdd.examples.wallet.models.events.WalletDeletedEvent;
import com.example.springdd.examples.wallet.models.events.WalletMoneyDepositedEvent;
import com.example.springdd.examples.wallet.models.events.WalletMoneyWithdrawnEvent;
import java.math.BigDecimal;
import java.util.Currency;
import org.apache.commons.lang3.Validate;

public final class Wallet extends Model<Wallet, Id<Wallet>, GenericState> {
    public final String ownerId;
    public final Currency currency;
    public final BigDecimal balance;

    private Wallet(Builder builder) {
        super(builder);
        this.ownerId = Validate.notNull(builder.ownerId, "ownerId cannot be null");
        this.currency = Validate.notNull(builder.currency, "currency cannot be null");
        this.balance = Validate.notNull(builder.balance, "balance cannot be null");
    }

    public static Wallet.Builder createWallet(String ownerId, Currency currency, BigDecimal balance) {
        final var id = Id.random(Wallet.class);
        return wallet().id(id)
                .state(ACTIVE)
                .ownerId(ownerId)
                .currency(currency)
                .balance(balance)
                .withEvent(new WalletCreatedEvent(id, ownerId, currency, balance));
    }

    public Builder copy() {
        return wallet().copyBase(this).ownerId(ownerId).currency(currency).balance(balance);
    }

    public Wallet deposit(BigDecimal amount) {
        Validate.notNull(amount, "amount cannot be null");
        Validate.isTrue(amount.compareTo(BigDecimal.ZERO) > 0, "Deposit amount must be positive");

        final var newBalance = balance.add(amount);
        return copy().withEvent(new WalletMoneyDepositedEvent(id, amount, balance.add(amount)))
                .balance(newBalance)
                .build();
    }

    public Wallet withdraw(BigDecimal amount) {
        Validate.notNull(amount, "amount cannot be null");
        Validate.isTrue(amount.compareTo(BigDecimal.ZERO) > 0, "Withdrawal amount must be positive");
        Validate.isTrue(amount.compareTo(balance) <= 0, "Insufficient funds");

        BigDecimal newBalance = balance.subtract(amount);
        return copy().withEvent(new WalletMoneyWithdrawnEvent(getId(), amount, newBalance))
                .balance(newBalance)
                .build();
    }

    public Wallet delete() {
        if (this.state.equals(DELETED)) {
            return this;
        }

        return copy().withEvent(new WalletDeletedEvent(id)).state(DELETED).build();
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

    public static final class Builder extends Model.Builder<Id<Wallet>, Builder, Wallet, GenericState> {
        private String ownerId;
        private Currency currency;
        private BigDecimal balance;

        private Builder() {}

        public static Builder wallet() {
            return new Builder();
        }

        public Builder ownerId(String ownerId) {
            this.ownerId = ownerId;
            return this;
        }

        public Builder currency(Currency currency) {
            this.currency = currency;
            return this;
        }

        public Builder balance(BigDecimal balance) {
            this.balance = balance;
            return this;
        }

        @Override
        public Wallet build() {
            return new Wallet(this);
        }
    }
}
