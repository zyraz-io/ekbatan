package io.ekbatan.test.event_pipeline.common.wallet.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.test.event_pipeline.common.wallet.models.Wallet;
import java.security.Principal;
import java.time.Clock;

public class WalletCreateAction extends Action<WalletCreateAction.Params, Wallet> {

    public WalletCreateAction(Clock clock) {
        super(clock);
    }

    @Override
    public Wallet perform(Principal principal, Params params) {
        var wallet = Wallet.createWallet(params.name, clock.instant()).build();
        plan.add(wallet);
        return wallet;
    }

    public record Params(String name) {}
}
