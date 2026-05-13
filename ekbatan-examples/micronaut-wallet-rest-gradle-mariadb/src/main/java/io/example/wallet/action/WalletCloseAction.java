package io.example.wallet.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.domain.Id;
import io.ekbatan.di.EkbatanAction;
import io.example.wallet.model.Wallet;
import io.example.wallet.repository.WalletRepository;
import java.security.Principal;
import java.time.Clock;

@EkbatanAction
public class WalletCloseAction extends Action<WalletCloseAction.Params, Wallet> {

    public record Params(Id<Wallet> walletId) {}

    private final WalletRepository walletRepository;

    public WalletCloseAction(Clock clock, WalletRepository walletRepository) {
        super(clock);
        this.walletRepository = walletRepository;
    }

    @Override
    protected Wallet perform(Principal principal, Params params) {
        final var wallet = walletRepository.getById(params.walletId().getValue());
        final var closed = wallet.close();
        return plan().update(closed);
    }
}
