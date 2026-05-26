package io.example.wallet.model;

import static io.example.wallet.model.WalletState.CLOSED;
import static io.example.wallet.model.WalletState.OPENED;

import io.ekbatan.core.domain.Model;
import io.ekbatan.core.domain.ShardedId;
import io.ekbatan.core.processor.AutoBuilder;
import io.ekbatan.core.shard.ShardIdentifier;
import io.example.wallet.model.events.WalletClosedEvent;
import io.example.wallet.model.events.WalletCreatedEvent;
import io.example.wallet.model.events.WalletMoneyDepositedEvent;
import io.example.wallet.model.events.WalletMoneyTransferredInEvent;
import io.example.wallet.model.events.WalletMoneyTransferredOutEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.apache.commons.lang3.Validate;

/**
 * Sharded {@code Wallet} - uses {@link ShardedId} instead of {@code Id} so the shard is encoded
 * directly into the wallet's UUID at creation time. Every subsequent read or update finds its way
 * back to the right physical database without an explicit shard parameter - see
 * {@link io.example.wallet.repository.WalletRepository} for the routing strategy.
 */
@AutoBuilder
public final class Wallet extends Model<Wallet, ShardedId<Wallet>, WalletState> {

    public final UUID ownerId;
    public final Currency currency;
    public final BigDecimal balance;

    Wallet(WalletBuilder builder) {
        super(builder);
        this.ownerId = Validate.notNull(builder.ownerId, "ownerId cannot be null");
        this.currency = Validate.notNull(builder.currency, "currency cannot be null");
        this.balance = Validate.notNull(builder.balance, "balance cannot be null");
    }

    /**
     * Creates a new wallet on the given shard. The shard bits are baked into the wallet's
     * {@link ShardedId} via {@link ShardedId#generate(Class, ShardIdentifier)} - every later
     * lookup by id decodes those bits to route to the right database.
     */
    public static WalletBuilder createWallet(
            ShardIdentifier shard, UUID ownerId, Currency currency, BigDecimal initialBalance, Instant createdDate) {
        final var id = ShardedId.generate(Wallet.class, shard);
        return WalletBuilder.wallet()
                .id(id)
                .state(OPENED)
                .ownerId(ownerId)
                .currency(currency)
                .balance(initialBalance)
                .createdDate(createdDate)
                .withInitialVersion()
                .withEvent(new WalletCreatedEvent(id, ownerId, currency, initialBalance));
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

    /**
     * Debit half of a cross-shard transfer. Emits a {@link WalletMoneyTransferredOutEvent} that
     * names the counterpart wallet - under {@code allowCrossShard=true} the counterpart can live
     * on a different physical database, and this event records the full action context so each
     * shard's row in {@code eventlog.events} tells the complete story on its own.
     */
    public Wallet transferOut(ShardedId<Wallet> toWallet, BigDecimal amount) {
        Validate.notNull(amount, "amount cannot be null");
        Validate.isTrue(amount.compareTo(BigDecimal.ZERO) > 0, "Transfer amount must be positive");
        Validate.isTrue(balance.compareTo(amount) >= 0, "Insufficient balance for transfer");

        final var newBalance = balance.subtract(amount);
        return copy().withEvent(new WalletMoneyTransferredOutEvent(id, toWallet, amount, newBalance))
                .balance(newBalance)
                .build();
    }

    /** Credit half of a cross-shard transfer. See {@link #transferOut(ShardedId, BigDecimal)}. */
    public Wallet transferIn(ShardedId<Wallet> fromWallet, BigDecimal amount) {
        Validate.notNull(amount, "amount cannot be null");
        Validate.isTrue(amount.compareTo(BigDecimal.ZERO) > 0, "Transfer amount must be positive");

        final var newBalance = balance.add(amount);
        return copy().withEvent(new WalletMoneyTransferredInEvent(id, fromWallet, amount, newBalance))
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
