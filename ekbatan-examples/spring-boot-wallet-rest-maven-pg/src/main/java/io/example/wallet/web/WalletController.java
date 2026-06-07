package io.example.wallet.web;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.domain.ShardedId;
import io.ekbatan.core.shard.ShardedUUID;
import io.example.wallet.action.WalletCloseAction;
import io.example.wallet.action.WalletCreateAction;
import io.example.wallet.action.WalletDepositMoneyAction;
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

    public record CreateRequest(String countryCode, UUID ownerId, String currency, BigDecimal initialBalance) {}

    public record DepositRequest(BigDecimal amount, String recipient) {}

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
                new WalletDepositMoneyAction.Params(toShardedId(id), body.amount(), body.recipient()));
        return WalletResponse.from(updated);
    }

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

    private static ShardedId<Wallet> toShardedId(UUID id) {
        return ShardedId.of(Wallet.class, ShardedUUID.from(id));
    }
}
