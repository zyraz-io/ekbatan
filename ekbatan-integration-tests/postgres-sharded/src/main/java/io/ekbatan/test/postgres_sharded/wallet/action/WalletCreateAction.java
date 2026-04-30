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

public class WalletCreateAction extends Action<WalletCreateAction.Params, Wallet> {

    private static final ShardIdentifier GLOBAL_SHARD = ShardIdentifier.of(0, 0);
    private static final ShardIdentifier MEXICO_SHARD = ShardIdentifier.of(1, 0);
    private static final ShardIdentifier AUSTRALIA_SHARD = ShardIdentifier.of(2, 0);

    public record Params(String countryCode) {}

    public WalletCreateAction(Clock clock) {
        super(clock);
    }

    @Override
    protected Wallet perform(Principal principal, Params params) throws Exception {
        final var shard = resolveShard(params.countryCode());
        final var wallet = createWallet(
                        shard, UUID.randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, clock.instant())
                .build();

        return plan().add(wallet);
    }

    private ShardIdentifier resolveShard(String countryCode) {
        return switch (countryCode) {
            case "MX" -> MEXICO_SHARD;
            case "AU" -> AUSTRALIA_SHARD;
            default -> GLOBAL_SHARD;
        };
    }
}
