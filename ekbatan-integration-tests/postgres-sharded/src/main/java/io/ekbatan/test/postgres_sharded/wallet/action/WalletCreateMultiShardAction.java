package io.ekbatan.test.postgres_sharded.wallet.action;

import static io.ekbatan.test.postgres_sharded.wallet.models.Wallet.createWallet;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.test.postgres_sharded.wallet.models.Wallet;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.util.Currency;
import java.util.UUID;

public class WalletCreateMultiShardAction extends Action<WalletCreateMultiShardAction.Params, Wallet> {

    private static final ShardIdentifier GLOBAL_SHARD = ShardIdentifier.of(0, 0);
    private static final ShardIdentifier MEXICO_SHARD = ShardIdentifier.of(1, 0);

    public record Params() {}

    public WalletCreateMultiShardAction(Clock clock) {
        super(clock);
    }

    @Override
    protected Wallet perform(Principal principal, Params params) throws Exception {
        var globalWallet = createWallet(
                        GLOBAL_SHARD, UUID.randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, clock.instant())
                .build();
        plan().add(globalWallet);

        var mexicoWallet = createWallet(
                        MEXICO_SHARD,
                        UUID.randomUUID(),
                        Currency.getInstance("MXN"),
                        BigDecimal.valueOf(100),
                        clock.instant())
                .build();
        plan().add(mexicoWallet);

        return globalWallet;
    }
}
