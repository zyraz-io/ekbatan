package io.ekbatan.core.test.model;

import static io.ekbatan.core.test.model.DummyBuilder.dummy;
import static io.ekbatan.core.test.model.DummyState.DELETED;
import static io.ekbatan.core.test.model.DummyState.OPENED;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.Model;
import io.ekbatan.core.processor.AutoBuilder;
import io.ekbatan.core.test.model.events.DummyCreatedEvent;
import io.ekbatan.core.test.model.events.DummyDeletedEvent;
import io.ekbatan.core.test.model.events.DummyMoneyDepositedEvent;
import io.ekbatan.core.test.model.events.DummyMoneyWithdrawnEvent;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.Validate;

@AutoBuilder
public final class Dummy extends Model<Dummy, Id<Dummy>, DummyState> {
    public final UUID ownerId;
    public final Currency currency;
    public final BigDecimal balance;
    public final List<String> aliases;

    Dummy(DummyBuilder builder) {
        super(builder);
        this.ownerId = Validate.notNull(builder.ownerId, "ownerId cannot be null");
        this.currency = Validate.notNull(builder.currency, "currency cannot be null");
        this.balance = Validate.notNull(builder.balance, "balance cannot be null");
        this.aliases = Objects.requireNonNullElse(builder.aliases, List.of());
    }

    public static DummyBuilder createDummy(UUID ownerId, Currency currency, BigDecimal balance) {
        final var id = Id.random(Dummy.class);
        return dummy().id(id)
                .state(OPENED)
                .ownerId(ownerId)
                .currency(currency)
                .balance(balance)
                .withInitialVersion()
                .withEvent(new DummyCreatedEvent(id, ownerId, currency, balance));
    }

    @Override
    public DummyBuilder copy() {
        return dummy().copyBase(this)
                .ownerId(ownerId)
                .currency(currency)
                .balance(balance)
                .aliases(aliases);
    }

    public Dummy deposit(BigDecimal amount) {
        Validate.notNull(amount, "amount cannot be null");
        Validate.isTrue(amount.compareTo(BigDecimal.ZERO) > 0, "Deposit amount must be positive");

        final var newBalance = balance.add(amount);
        return copy().withEvent(new DummyMoneyDepositedEvent(id, amount, newBalance))
                .balance(newBalance)
                .build();
    }

    public Dummy withdraw(BigDecimal amount) {
        Validate.notNull(amount, "amount cannot be null");
        Validate.isTrue(amount.compareTo(BigDecimal.ZERO) > 0, "Withdrawal amount must be positive");
        Validate.isTrue(amount.compareTo(balance) <= 0, "Insufficient funds");

        BigDecimal newBalance = balance.subtract(amount);
        return copy().withEvent(new DummyMoneyWithdrawnEvent(getId(), amount, newBalance))
                .balance(newBalance)
                .build();
    }

    public Dummy delete() {
        if (this.state.equals(DELETED)) {
            return this;
        }

        return copy().withEvent(new DummyDeletedEvent(id)).state(DELETED).build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        Dummy dummy = (Dummy) o;
        return ownerId.equals(dummy.ownerId)
                && currency.equals(dummy.currency)
                && balance.compareTo(dummy.balance) == 0;
    }
}
