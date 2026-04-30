package io.ekbatan.test.event_pipeline.common.wallet.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.domain.Id;
import io.ekbatan.test.event_pipeline.common.wallet.models.Wallet;
import io.ekbatan.test.event_pipeline.common.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;

public class WalletDepositMoneyAction extends Action<WalletDepositMoneyAction.Params, Wallet> {

    private final WalletRepository walletRepository;

    public WalletDepositMoneyAction(Clock clock, WalletRepository walletRepository) {
        super(clock);
        this.walletRepository = walletRepository;
    }

    @Override
    public Wallet perform(Principal principal, Params params) throws Exception {
        var wallet = walletRepository.getById(params.walletId.getValue());
        var updated = wallet.deposit(params.amount);
        plan().update(updated);
        return updated;
    }

    public record Params(Id<Wallet> walletId, BigDecimal amount) {}
}
