package io.ekbatan.test.postgres_simple.wallet.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.domain.Id;
import io.ekbatan.test.postgres_simple.wallet.models.Wallet;
import io.ekbatan.test.postgres_simple.wallet.repository.WalletRepository;
import java.security.Principal;
import java.time.Clock;

public class WalletCloseAction extends Action<WalletCloseAction.Params, Wallet> {

    public record Params(Id<Wallet> walletId) {}

    private final WalletRepository walletRepository;

    public WalletCloseAction(Clock clock, WalletRepository walletRepository) {
        super(clock);
        this.walletRepository = walletRepository;
    }

    @Override
    protected Wallet perform(Principal principal, Params params) throws Exception {
        final var wallet = walletRepository.getById(params.walletId().getValue());
        final var closed = wallet.close();
        return plan.update(closed);
    }
}
