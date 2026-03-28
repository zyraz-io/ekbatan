package io.ekbatan.test.postgres_single_table_events.wallet.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.domain.Id;
import io.ekbatan.test.postgres_single_table_events.wallet.models.Wallet;
import io.ekbatan.test.postgres_single_table_events.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;

public class WalletDepositMoneyAction extends Action<WalletDepositMoneyAction.Params, Wallet> {

    public final WalletRepository walletRepository;

    public record Params(Id<Wallet> walletId) {}

    public WalletDepositMoneyAction(Clock clock, WalletRepository walletRepository) {
        super(clock);
        this.walletRepository = walletRepository;
    }

    @Override
    protected Wallet perform(Principal principal, Params params) throws Exception {
        final var wallet = walletRepository.getById(params.walletId.getId());

        return plan.update(wallet.deposit(BigDecimal.TEN));
    }
}
