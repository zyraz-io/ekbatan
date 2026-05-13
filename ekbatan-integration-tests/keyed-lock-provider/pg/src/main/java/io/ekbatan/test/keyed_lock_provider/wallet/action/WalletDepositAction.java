package io.ekbatan.test.keyed_lock_provider.wallet.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.domain.Id;
import io.ekbatan.test.keyed_lock_provider.wallet.models.Wallet;
import io.ekbatan.test.keyed_lock_provider.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;

/**
 * Plain deposit Action with no lock injection. The action is a pure "read-modify-write" step
 * over the {@code wallets} table — locking is a coordination policy applied at the
 * <em>caller</em> (around {@code executor.execute(...)}), not inside {@code perform()}.
 *
 * <p>The framework opens its transaction <strong>after</strong> {@code perform()} returns the
 * plan and commits when {@code ActionExecutor.persistChanges()} finishes. A lease acquired
 * inside {@code perform()} closes before that commit, so a concurrent caller could read
 * pre-commit state and trip the framework's optimistic-locking version check. Keeping the
 * lease around {@code executor.execute(...)} guarantees the lease spans both phases. See
 * {@code docs/database/keyed-locks.md#where-to-acquire-the-lock}.
 */
public class WalletDepositAction extends Action<WalletDepositAction.Params, Wallet> {

    public record Params(Id<Wallet> walletId, BigDecimal amount) {}

    private final WalletRepository walletRepository;

    public WalletDepositAction(Clock clock, WalletRepository walletRepository) {
        super(clock);
        this.walletRepository = walletRepository;
    }

    @Override
    protected Wallet perform(Principal principal, Params params) throws Exception {
        final var wallet = walletRepository.getById(params.walletId().getValue());
        final var updated = wallet.deposit(params.amount());
        return plan().update(updated);
    }
}
