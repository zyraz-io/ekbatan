package io.ekbatan.test.postgres_simple.wallet.action;

import static io.ekbatan.test.postgres_simple.wallet.models.Wallet.createWallet;

import io.ekbatan.core.action.Action;
import io.ekbatan.test.postgres_simple.wallet.models.Wallet;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.util.Currency;
import java.util.UUID;

public class WalletCreateAction extends Action<WalletCreateAction.Params, Wallet> {

    public record Params(UUID ownerId, String currencyCode, BigDecimal initialBalance) {}

    public WalletCreateAction(Clock clock) {
        super(clock);
    }

    @Override
    protected Wallet perform(Principal principal, Params params) {
        final var wallet = createWallet(
                        params.ownerId(),
                        Currency.getInstance(params.currencyCode()),
                        params.initialBalance(),
                        clock.instant())
                .build();
        return plan.add(wallet);
    }
}
