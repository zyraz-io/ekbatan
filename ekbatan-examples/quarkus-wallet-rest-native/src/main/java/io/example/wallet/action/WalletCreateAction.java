package io.example.wallet.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.di.EkbatanAction;
import io.example.wallet.model.Wallet;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.util.Currency;
import java.util.UUID;

@EkbatanAction
public class WalletCreateAction extends Action<WalletCreateAction.Params, Wallet> {

    public record Params(UUID ownerId, Currency currency, BigDecimal initialBalance) {}

    public WalletCreateAction(Clock clock) {
        super(clock);
    }

    @Override
    protected Wallet perform(Principal principal, Params params) {
        final var wallet = Wallet.createWallet(
                        params.ownerId(), params.currency(), params.initialBalance(), clock.instant())
                .build();
        return plan().add(wallet);
    }
}
