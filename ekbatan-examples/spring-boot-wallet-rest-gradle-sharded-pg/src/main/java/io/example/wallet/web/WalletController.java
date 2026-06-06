package io.example.wallet.web;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.action.ExecutionConfiguration;
import io.ekbatan.core.domain.ShardedId;
import io.ekbatan.core.shard.ShardedUUID;
import io.example.wallet.action.WalletCloseAction;
import io.example.wallet.action.WalletCreateAction;
import io.example.wallet.action.WalletDepositMoneyAction;
import io.example.wallet.action.WalletTransferAction;
import io.example.wallet.model.Wallet;
import io.example.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final ActionExecutor executor;
    private final WalletRepository walletRepository;

    public WalletController(ActionExecutor executor, WalletRepository walletRepository) {
        this.executor = executor;
        this.walletRepository = walletRepository;
    }

    /** Country code maps to a shard inside {@code WalletCreateAction.resolveShard(...)}. */
    public record CreateRequest(String countryCode, UUID ownerId, String currency, BigDecimal initialBalance) {}

    public record DepositRequest(BigDecimal amount) {}

    public record TransferRequest(UUID fromWalletId, UUID toWalletId, BigDecimal amount) {}

    public record WalletResponse(
            UUID id,
            int shardGroup,
            int shardMember,
            UUID ownerId,
            String currency,
            BigDecimal balance,
            String state,
            Long version) {
        static WalletResponse from(Wallet w) {
            // Decode the shard from the wallet's id - useful for the example so callers can see
            // which physical database the wallet lives on without poking at the DatabaseRegistry.
            final var shard = w.id.resolveShardIdentifier();
            return new WalletResponse(
                    w.id.getValue(),
                    shard.group,
                    shard.member,
                    w.ownerId,
                    w.currency.getCurrencyCode(),
                    w.balance,
                    w.state.name(),
                    w.version);
        }
    }

    @PostMapping
    public ResponseEntity<WalletResponse> create(@RequestBody CreateRequest body) throws Exception {
        final var wallet = executor.execute(
                () -> "rest-user",
                WalletCreateAction.class,
                new WalletCreateAction.Params(
                        body.countryCode(),
                        body.ownerId(),
                        Currency.getInstance(body.currency()),
                        body.initialBalance()));
        return ResponseEntity.status(HttpStatus.CREATED).body(WalletResponse.from(wallet));
    }

    @PostMapping("/{id}/deposits")
    public WalletResponse deposit(@PathVariable UUID id, @RequestBody DepositRequest body) throws Exception {
        final var updated = executor.execute(
                () -> "rest-user",
                WalletDepositMoneyAction.class,
                new WalletDepositMoneyAction.Params(toShardedId(id), body.amount()));
        return WalletResponse.from(updated);
    }

    /**
     * Cross-shard transfer. Opts into {@code allowCrossShard(true)} unconditionally - for two
     * wallets on the same shard this is a no-op (the executor sees one shard in the plan and
     * runs it as a regular single-shard action). For two wallets on different shards, the
     * executor opens two independent transactions and duplicates the {@code eventlog.events}
     * row to each shard.
     *
     * <p>This endpoint is a sharding mechanics demo, not the recommended production pattern for
     * real money transfers. Use the saga example for transfer workflows that need explicit
     * compensation when a later step fails.
     */
    @PostMapping("/transfers")
    public TransferResponse transfer(@RequestBody TransferRequest body) throws Exception {
        final var config = ExecutionConfiguration.Builder.executionConfiguration()
                .allowCrossShard(true)
                .build();
        final var result = executor.execute(
                () -> "rest-user",
                WalletTransferAction.class,
                new WalletTransferAction.Params(
                        toShardedId(body.fromWalletId()), toShardedId(body.toWalletId()), body.amount()),
                config);
        return new TransferResponse(WalletResponse.from(result.fromWallet()), WalletResponse.from(result.toWallet()));
    }

    public record TransferResponse(WalletResponse from, WalletResponse to) {}

    @PostMapping("/{id}/close")
    public WalletResponse close(@PathVariable UUID id) throws Exception {
        final var closed = executor.execute(
                () -> "rest-user", WalletCloseAction.class, new WalletCloseAction.Params(toShardedId(id)));
        return WalletResponse.from(closed);
    }

    @GetMapping("/{id}")
    public ResponseEntity<WalletResponse> get(@PathVariable UUID id) {
        return walletRepository
                .findById(id)
                .map(WalletResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** REST callers pass raw UUIDs; the action layer expects type-safe shard-aware ids. */
    private static ShardedId<Wallet> toShardedId(UUID id) {
        return ShardedId.of(Wallet.class, ShardedUUID.from(id));
    }
}
