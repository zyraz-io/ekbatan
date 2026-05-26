package io.example.wallet.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.di.EkbatanAction;
import io.example.wallet.model.Wallet;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.util.Currency;
import java.util.UUID;

/**
 * Creates a wallet on a shard chosen by {@code countryCode}.
 *
 * <ul>
 *   <li>{@code "MX"} -> Mexico shard (group=1, member=0)</li>
 *   <li>{@code "AU"} -> Australia shard (group=2, member=0) - not registered in this example;
 *       falls back to the default shard via {@code DatabaseRegistry.effectiveShard}. The wallet's
 *       UUID still encodes the Australia shard, so when the Australia DB is provisioned later, no
 *       data migration is needed.</li>
 *   <li>anything else -> global shard (group=0, member=0), the default</li>
 * </ul>
 *
 * <p>The shard travels with the wallet's {@code ShardedId}; every later {@code findById} or
 * {@code update} decodes the shard from the id and routes accordingly.
 */
@EkbatanAction
public class WalletCreateAction extends Action<WalletCreateAction.Params, Wallet> {

    public static final ShardIdentifier GLOBAL_SHARD = ShardIdentifier.of(0, 0);
    public static final ShardIdentifier MEXICO_SHARD = ShardIdentifier.of(1, 0);
    public static final ShardIdentifier AUSTRALIA_SHARD = ShardIdentifier.of(2, 0);

    public record Params(String countryCode, UUID ownerId, Currency currency, BigDecimal initialBalance) {}

    public WalletCreateAction(Clock clock) {
        super(clock);
    }

    @Override
    protected Wallet perform(Principal principal, Params params) {
        final var shard = resolveShard(params.countryCode());
        final var wallet = Wallet.createWallet(
                        shard, params.ownerId(), params.currency(), params.initialBalance(), clock.instant())
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
