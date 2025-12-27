package io.ekbatan.examples.wallet.action;

import static io.ekbatan.examples.wallet.models.Wallet.createWallet;

import io.ekbatan.core.action.Action;
import io.ekbatan.examples.wallet.models.Wallet;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.Currency;
import java.util.UUID;

public class WalletCreateAction extends Action<WalletCreateAction.Params, Wallet> {

    public record Params() {}

    @Override
    protected Wallet perform(Principal principal, Params params) throws Exception {
        final var wallet = createWallet(UUID.randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();

        return plan.add(wallet);
    }
}
