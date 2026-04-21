package io.ekbatan.test.keyed_lock_provider.wallet.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.concurrent.KeyedLockProvider;
import io.ekbatan.core.domain.Id;
import io.ekbatan.test.keyed_lock_provider.wallet.models.Wallet;
import io.ekbatan.test.keyed_lock_provider.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;

/**
 * Mirrors the README example exactly — including the deliberate use of the no-wait-timeout
 * {@link KeyedLockProvider#acquire(String, Duration)} variant. The 10-second {@link Duration}
 * is the {@code maxHold} (max time to keep the lock once acquired), <em>not</em> a wait
 * timeout. If another instance holds the lock, this thread blocks indefinitely until that
 * holder releases or until this thread is interrupted.
 */
public class WalletDepositAction extends Action<WalletDepositAction.Params, Wallet> {

    public record Params(Id<Wallet> walletId, BigDecimal amount) {}

    private final WalletRepository walletRepository;
    private final KeyedLockProvider lockProvider;

    public WalletDepositAction(Clock clock, WalletRepository walletRepository, KeyedLockProvider lockProvider) {
        super(clock);
        this.walletRepository = walletRepository;
        this.lockProvider = lockProvider;
    }

    @Override
    protected Wallet perform(Principal principal, Params params) throws Exception {
        try (var lockLease = lockProvider.acquire("wallet:" + params.walletId(), Duration.ofSeconds(10))) {
            final var wallet = walletRepository.getById(params.walletId().getValue());
            final var updated = wallet.deposit(params.amount());
            return plan.update(updated);
        }
    }
}
