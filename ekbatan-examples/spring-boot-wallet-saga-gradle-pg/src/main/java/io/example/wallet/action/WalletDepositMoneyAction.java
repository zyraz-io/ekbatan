package io.example.wallet.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.domain.Id;
import io.ekbatan.di.EkbatanAction;
import io.example.wallet.model.Wallet;
import io.example.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;

/** Single-aggregate deposit — kept for ad-hoc wallet top-ups outside the transfer saga. */
@EkbatanAction
public class WalletDepositMoneyAction extends Action<WalletDepositMoneyAction.Params, Wallet> {

    public record Params(Id<Wallet> walletId, BigDecimal amount) {}

    private final WalletRepository walletRepository;

    public WalletDepositMoneyAction(Clock clock, WalletRepository walletRepository) {
        super(clock);
        this.walletRepository = walletRepository;
    }

    @Override
    protected Wallet perform(Principal principal, Params params) {
        final var wallet = walletRepository.getById(params.walletId().getValue());
        final var updated = wallet.deposit(params.amount());
        return plan().update(updated);
    }
}
