package io.ekbatan.test.event_pipeline.common.wallet.models;

import io.ekbatan.core.domain.GenericState;
import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.Model;
import io.ekbatan.core.processor.AutoBuilder;
import io.ekbatan.test.event_pipeline.common.wallet.models.events.WalletCreatedEvent;
import io.ekbatan.test.event_pipeline.common.wallet.models.events.WalletMoneyDepositedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import org.apache.commons.lang3.Validate;

@AutoBuilder
public final class Wallet extends Model<Wallet, Id<Wallet>, GenericState> {
    public final String name;
    public final BigDecimal balance;

    Wallet(WalletBuilder builder) {
        super(builder);
        this.name = Validate.notNull(builder.name, "name cannot be null");
        this.balance = Validate.notNull(builder.balance, "balance cannot be null");
    }

    public static WalletBuilder createWallet(String name, Instant now) {
        var id = Id.random(Wallet.class);
        return WalletBuilder.wallet()
                .id(id)
                .state(GenericState.ACTIVE)
                .name(name)
                .balance(BigDecimal.ZERO)
                .createdDate(now)
                .withInitialVersion()
                .withEvent(new WalletCreatedEvent(id, name));
    }

    public Wallet deposit(BigDecimal amount) {
        return copy().balance(balance.add(amount))
                .withEvent(new WalletMoneyDepositedEvent(id, amount))
                .build();
    }

    @Override
    public WalletBuilder copy() {
        return WalletBuilder.wallet().copyBase(this).name(name).balance(balance);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        Wallet wallet = (Wallet) o;
        return name.equals(wallet.name) && balance.compareTo(wallet.balance) == 0;
    }
}
