package io.ekbatan.test.postgres_sharded.wallet.action;

import static io.ekbatan.test.postgres_sharded.wallet.models.Wallet.createWallet;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.test.postgres_sharded.wallet.models.Wallet;
import io.ekbatan.test.postgres_sharded.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.util.Currency;
import java.util.UUID;

/**
 * Deliberately produces a partial cross-shard commit: stages a fresh wallet on the global shard
 * and a stale-version update of an existing wallet on the Mexico shard.
 *
 * <p>{@code groupChangesByShard} walks additions before updates, so the executor opens the global
 * shard's transaction first and commits it, then fails the Mexico transaction on the optimistic-lock
 * version check. That leaves the global row committed while Mexico rolls back - exactly the
 * situation {@code ActionExecutor} logs and traces as a partial cross-shard commit.
 *
 * <p>Only useful for testing the partial-commit path; never a pattern to copy into an application.
 */
public class WalletCreateThenFailMultiShardAction extends Action<WalletCreateThenFailMultiShardAction.Params, Wallet> {

    private static final ShardIdentifier GLOBAL_SHARD = ShardIdentifier.of(0, 0);

    /** Version guaranteed not to match any freshly-created wallet, so the update affects zero rows. */
    private static final long STALE_VERSION = 999L;

    /**
     * @param mexicoWalletId id of an existing wallet on the Mexico shard.
     */
    public record Params(UUID mexicoWalletId) {}

    private final WalletRepository walletRepository;

    public WalletCreateThenFailMultiShardAction(Clock clock, WalletRepository walletRepository) {
        super(clock);
        this.walletRepository = walletRepository;
    }

    @Override
    protected Wallet perform(Principal principal, Params params) throws Exception {
        final var globalWallet = createWallet(
                        GLOBAL_SHARD, UUID.randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, clock.instant())
                .build();
        plan().add(globalWallet);

        final var mexicoWallet = walletRepository.getById(params.mexicoWalletId());
        plan().update(mexicoWallet.copy().version(STALE_VERSION).build());

        return globalWallet;
    }
}
