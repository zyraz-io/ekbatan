package io.example.wallet.web;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.domain.Id;
import io.example.wallet.action.InitiateTransferAction;
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

    public record CreateRequest(UUID ownerId, String currency, BigDecimal initialBalance) {}

    public record DepositRequest(BigDecimal amount) {}

    public record TransferRequest(UUID fromWalletId, UUID toWalletId, BigDecimal amount) {}

    public record TransferResponse(UUID transferId, WalletResponse fromWallet) {}

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
                new WalletDepositMoneyAction.Params(Id.of(Wallet.class, id), body.amount()));
        return WalletResponse.from(updated);
    }

    /**
     * Saga entry point. Synchronously runs step 1 (debit source + emit TransferInitiated) and
     * returns 202 with the transferId. Steps 2 and 3 (credit destination, or fail + refund)
     * happen asynchronously via the local-event-handler.
     */
    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> transfer(@RequestBody TransferRequest body) throws Exception {
        final var result = executor.execute(
                () -> "rest-user",
                InitiateTransferAction.class,
                new InitiateTransferAction.Params(
                        Id.of(Wallet.class, body.fromWalletId()),
                        Id.of(Wallet.class, body.toWalletId()),
                        body.amount()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new TransferResponse(result.transferId(), WalletResponse.from(result.sourceWallet())));
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
