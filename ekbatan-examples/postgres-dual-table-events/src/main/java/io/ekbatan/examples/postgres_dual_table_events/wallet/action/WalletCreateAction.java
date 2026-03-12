package io.ekbatan.examples.postgres_dual_table_events.wallet.action;

import static io.ekbatan.examples.postgres_dual_table_events.wallet.models.Wallet.createWallet;

import io.ekbatan.core.action.Action;
import io.ekbatan.examples.postgres_dual_table_events.wallet.models.Wallet;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.util.Currency;
import java.util.UUID;

public class WalletCreateAction extends Action<WalletCreateAction.Params, Wallet> {

    public record Params() {}

    public WalletCreateAction(Clock clock) {
        super(clock);
    }

    @Override
    protected Wallet perform(Principal principal, Params params) throws Exception {
        final var wallet = createWallet(UUID.randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, clock.instant())
                .build();

        return plan.add(wallet);
    }
}
