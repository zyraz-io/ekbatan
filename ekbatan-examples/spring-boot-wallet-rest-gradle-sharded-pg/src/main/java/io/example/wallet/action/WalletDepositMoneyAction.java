package io.example.wallet.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.domain.ShardedId;
import io.ekbatan.di.EkbatanAction;
import io.example.wallet.model.Wallet;
import io.example.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;

/**
 * Single-shard deposit. The wallet's {@link ShardedId} encodes the shard bits, so
 * {@code walletRepository.getById} routes to the correct database automatically - and the
 * subsequent {@code plan().update(...)} commits on the same shard. No cross-shard semantics here.
 */
@EkbatanAction
public class WalletDepositMoneyAction extends Action<WalletDepositMoneyAction.Params, Wallet> {

    public record Params(ShardedId<Wallet> walletId, BigDecimal amount) {}

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
