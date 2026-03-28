package io.ekbatan.test.postgres_sharded.wallet.models;

import static io.ekbatan.test.postgres_sharded.wallet.models.WalletState.OPENED;

import io.ekbatan.core.domain.Model;
import io.ekbatan.core.domain.ShardedId;
import io.ekbatan.core.processor.AutoBuilder;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.test.postgres_sharded.wallet.models.events.WalletCreatedEvent;
import io.ekbatan.test.postgres_sharded.wallet.models.events.WalletMoneyDepositedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.Validate;

@AutoBuilder
public final class Wallet extends Model<Wallet, ShardedId<Wallet>, WalletState> {
    public final UUID ownerId;
    public final Currency currency;
    public final BigDecimal balance;
    public final List<String> aliases;

    Wallet(WalletBuilder builder) {
        super(builder);
        this.ownerId = Validate.notNull(builder.ownerId, "ownerId cannot be null");
        this.currency = Validate.notNull(builder.currency, "currency cannot be null");
        this.balance = Validate.notNull(builder.balance, "balance cannot be null");
        this.aliases = Objects.requireNonNullElse(builder.aliases, List.of());
    }

    public static WalletBuilder createWallet(
            ShardIdentifier shard, UUID ownerId, Currency currency, BigDecimal balance, Instant createdDate) {
        final var id = ShardedId.generate(Wallet.class, shard);
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
                .balance(balance)
                .aliases(aliases);
    }

    public Wallet deposit(BigDecimal amount) {
        Validate.notNull(amount, "amount cannot be null");
        Validate.isTrue(amount.compareTo(BigDecimal.ZERO) > 0, "Deposit amount must be positive");

        final var newBalance = balance.add(amount);
        return copy().withEvent(new WalletMoneyDepositedEvent(id, amount, newBalance))
                .balance(newBalance)
                .build();
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
