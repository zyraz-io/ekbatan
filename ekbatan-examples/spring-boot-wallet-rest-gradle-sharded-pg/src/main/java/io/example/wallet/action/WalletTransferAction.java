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
 * Cross-shard transfer between two wallets. The wallets may live on the same shard <em>or</em>
 * on different shards - when they do live on different shards, the caller must opt into
 * cross-shard execution via {@code ExecutionConfiguration.Builder.allowCrossShard(true)},
 * otherwise the framework throws {@code CrossShardException} from {@code ActionExecutor}.
 *
 * <p><strong>Warning:</strong> this action demonstrates cross-shard execution mechanics. It is
 * not the recommended production pattern for real money transfers. When shards differ, each
 * shard commits independently; there is no distributed transaction and no two-phase commit.
 * For production transfer workflows, prefer the saga example
 * {@code spring-boot-wallet-saga-gradle-pg}, which splits the workflow into per-step actions
 * and compensates failures explicitly.
 *
 * <p>The action plans two updates: the source wallet (debit) and the destination wallet (credit).
 * Each plan entry resolves to a shard via the {@link WalletRepository}'s sharding strategy. When
 * those shards differ:
 *
 * <ul>
 *   <li>Each shard runs its own transaction - there is no 2PC, and partial failures are
 *       possible. Choose this only for genuinely cross-region operations that your domain has
 *       designed to be eventually consistent.</li>
 *   <li>The {@code eventlog.events} row for this action is duplicated to <em>both</em> shards
 *       with the same {@code action_id} UUID - each shard's row carries the full transfer
 *       context (debit event on the source, credit event on the destination) so downstream CDC
 *       consumers on either shard see the complete picture.</li>
 * </ul>
 */
@EkbatanAction
public class WalletTransferAction extends Action<WalletTransferAction.Params, WalletTransferAction.Result> {

    public record Params(ShardedId<Wallet> fromWalletId, ShardedId<Wallet> toWalletId, BigDecimal amount) {}

    public record Result(Wallet fromWallet, Wallet toWallet) {}

    private final WalletRepository walletRepository;

    public WalletTransferAction(Clock clock, WalletRepository walletRepository) {
        super(clock);
        this.walletRepository = walletRepository;
    }

    @Override
    protected Result perform(Principal principal, Params params) {
        // Repository.findById decodes each wallet's shard from its ShardedUUID and routes the
        // query to that shard automatically - no explicit ShardIdentifier handoff.
        final var fromWallet = walletRepository.getById(params.fromWalletId().getValue());
        final var toWallet = walletRepository.getById(params.toWalletId().getValue());

        final var debited = fromWallet.transferOut(params.toWalletId(), params.amount());
        final var credited = toWallet.transferIn(params.fromWalletId(), params.amount());

        plan().update(debited);
        plan().update(credited);
        return new Result(debited, credited);
    }
}
