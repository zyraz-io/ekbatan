package io.example.wallet.repository;

import static io.example.wallet.generated.jooq.public_schema.tables.Wallets.WALLETS;

import io.ekbatan.core.domain.ShardedId;
import io.ekbatan.core.repository.ModelRepository;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.EmbeddedBitsShardingStrategy;
import io.ekbatan.core.shard.ShardedUUID;
import io.ekbatan.di.EkbatanRepository;
import io.example.wallet.generated.jooq.public_schema.tables.Wallets;
import io.example.wallet.generated.jooq.public_schema.tables.records.WalletsRecord;
import io.example.wallet.model.Wallet;
import io.example.wallet.model.WalletBuilder;
import io.example.wallet.model.WalletState;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/**
 * Sharded wallet repository. Two deltas from a non-sharded {@code ModelRepository}:
 *
 * <ol>
 *   <li>The constructor passes an {@link EmbeddedBitsShardingStrategy} to {@code super(...)} —
 *       the strategy decodes the shard from the UUID's embedded bits, so {@code findById(uuid)}
 *       automatically routes to the wallet's shard with no caller cooperation.</li>
 *   <li>{@code fromRecord} wraps the raw UUID in a {@link ShardedId} via {@link ShardedUUID#from}
 *       — the rest of the framework treats wallet IDs as shard-aware.</li>
 * </ol>
 *
 * <p>Custom queries that drop into raw jOOQ should target a specific shard via the ID-aware
 * accessors — {@code readonlyDb(walletId)}, {@code db(walletId)}, {@code txDbElseDb(walletId)} —
 * instead of {@code readonlyDb()} which scatter-gathers across every shard. The
 * {@link #findAllByOwnerId(UUID)} method below is intentionally scatter-gather because owner-id
 * is not shard-aware.
 */
@EkbatanRepository
public class WalletRepository extends ModelRepository<Wallet, WalletsRecord, Wallets, UUID> {

    public WalletRepository(DatabaseRegistry databaseRegistry) {
        super(Wallet.class, WALLETS, WALLETS.ID, databaseRegistry, new EmbeddedBitsShardingStrategy());
    }

    /**
     * Scatter-gather across all shards — owner id is not encoded in the wallet's UUID, so we
     * have to ask each shard. For id-aware queries, use {@code findById} or the {@code db(id)}
     * accessor.
     */
    public List<Wallet> findAllByOwnerId(UUID ownerId) {
        return readonlyDbs().stream()
                .flatMap(ctx -> ctx
                        .selectFrom(WALLETS)
                        .where(WALLETS.OWNER_ID.eq(ownerId))
                        .and(WALLETS.STATE.ne(WalletState.DELETED.name()))
                        .fetch(this::fromRecord)
                        .stream())
                .toList();
    }

    @Override
    public Wallet fromRecord(WalletsRecord r) {
        return WalletBuilder.wallet()
                .id(ShardedId.of(Wallet.class, ShardedUUID.from(r.getId())))
                .version(r.getVersion())
                .state(WalletState.valueOf(r.getState()))
                .ownerId(r.getOwnerId())
                .currency(Currency.getInstance(r.getCurrency()))
                .balance(r.getBalance())
                .createdDate(r.getCreatedDate())
                .updatedDate(r.getUpdatedDate())
                .build();
    }

    @Override
    public WalletsRecord toRecord(Wallet w) {
        return new WalletsRecord(
                w.id.getValue(),
                w.version,
                w.state.name(),
                w.ownerId,
                w.currency.getCurrencyCode(),
                w.balance,
                w.createdDate,
                w.updatedDate);
    }
}
