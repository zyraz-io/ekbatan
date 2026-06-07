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

@EkbatanAction
public class WalletCreateAction extends Action<WalletCreateAction.Params, Wallet> {

    public static final ShardIdentifier GLOBAL_SHARD = ShardIdentifier.of(0, 0);
    public static final ShardIdentifier MEXICO_SHARD = ShardIdentifier.of(1, 0);

    public record Params(String countryCode, UUID ownerId, Currency currency, BigDecimal initialBalance) {
        public Params(UUID ownerId, Currency currency, BigDecimal initialBalance) {
            this(null, ownerId, currency, initialBalance);
        }
    }

    public WalletCreateAction(Clock clock) {
        super(clock);
    }

    @Override
    protected Wallet perform(Principal principal, Params params) {
        final var wallet = Wallet.createWallet(
                        resolveShard(params.countryCode()),
                        params.ownerId(),
                        params.currency(),
                        params.initialBalance(),
                        clock.instant())
                .build();
        return plan().add(wallet);
    }

    private ShardIdentifier resolveShard(String countryCode) {
        if ("MX".equalsIgnoreCase(countryCode)) {
            return MEXICO_SHARD;
        }
        return GLOBAL_SHARD;
    }
}
