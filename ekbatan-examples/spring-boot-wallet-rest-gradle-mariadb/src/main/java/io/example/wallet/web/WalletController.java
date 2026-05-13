package io.example.wallet.web;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.concurrent.KeyedLockProvider;
import io.ekbatan.core.domain.Id;
import io.example.wallet.action.WalletCloseAction;
import io.example.wallet.action.WalletCreateAction;
import io.example.wallet.action.WalletDepositMoneyAction;
import io.example.wallet.model.Wallet;
import io.example.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.time.Duration;
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

    /** Auto-release safety net for held leases. Real workloads pick this from the SLA they want. */
    private static final Duration LOCK_MAX_HOLD = Duration.ofSeconds(10);

    private final ActionExecutor executor;
    private final WalletRepository walletRepository;
    private final KeyedLockProvider lockProvider;

    public WalletController(
            ActionExecutor executor, WalletRepository walletRepository, KeyedLockProvider lockProvider) {
        this.executor = executor;
        this.walletRepository = walletRepository;
        this.lockProvider = lockProvider;
    }

    public record CreateRequest(UUID ownerId, String currency, BigDecimal initialBalance) {}

    public record DepositRequest(BigDecimal amount, String recipient) {}

    public record WalletResponse(
            UUID id, UUID ownerId, String currency, BigDecimal balance, String state, Long version) {
        static WalletResponse from(Wallet w) {
            return new WalletResponse(
                    w.id.getValue(), w.ownerId, w.currency.getCurrencyCode(), w.balance, w.state.name(), w.version);
        }
    }

    @PostMapping
    public ResponseEntity<WalletResponse> create(@RequestBody CreateRequest body) throws Exception {
        final var wallet = executor.execute(
                () -> "rest-user",
                WalletCreateAction.class,
                new WalletCreateAction.Params(
                        body.ownerId(), Currency.getInstance(body.currency()), body.initialBalance()));
        return ResponseEntity.status(HttpStatus.CREATED).body(WalletResponse.from(wallet));
    }

    @PostMapping("/{id}/deposits")
    public WalletResponse deposit(@PathVariable UUID id, @RequestBody DepositRequest body) throws Exception {
        final var updated = executor.execute(
                () -> "rest-user",
                WalletDepositMoneyAction.class,
                new WalletDepositMoneyAction.Params(Id.of(Wallet.class, id), body.amount(), body.recipient()));
        return WalletResponse.from(updated);
    }

    @PostMapping("/{id}/close")
    public WalletResponse close(@PathVariable UUID id) throws Exception {
        final var closed = executor.execute(
                () -> "rest-user", WalletCloseAction.class, new WalletCloseAction.Params(Id.of(Wallet.class, id)));
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
}
