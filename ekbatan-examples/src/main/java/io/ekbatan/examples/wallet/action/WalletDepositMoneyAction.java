package io.ekbatan.examples.wallet.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.domain.Id;
import io.ekbatan.examples.wallet.models.Wallet;
import io.ekbatan.examples.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.security.Principal;

public class WalletDepositMoneyAction extends Action<WalletDepositMoneyAction.Params, Wallet> {

    public final WalletRepository walletRepository;

    public record Params(Id<Wallet> walletId) {}

    public WalletDepositMoneyAction(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Override
    protected Wallet tryPerform(Principal principal, Params params) throws Exception {
        final var wallet = walletRepository.getById(params.walletId.getId());

        return plan.update(wallet.deposit(BigDecimal.TEN));
    }
}
